(ns #^{:author "Hugo Duncan"}
  pallet.core
"Pallet is used to provision configured compute nodes using jclouds and chef.

It uses a declaritive map for specifying the number of nodes with a given tag.
Each tag is used to look up a machine image template specification (in
jclouds), and to lookup configuration information (in chef).  The converge
function then tries to bring you compute servers into alignment with your
declared counts and configurations.

The bootstrap process for new compute nodes installs a user with sudo
permissions, using the specified username and password. The installed user is
used to execute the chef cookbooks.

Once the nodes are bootstrapped, and fall all existing nodes
the configured node information is written to the \"compute-nodes\" cookbook
before chef is run, and this provides a :compute_nodes attribute.  The
compute-nodes cookbook is expected to exist in the site-cookbooks of the
chef-repository you specify with `with-chef-repository`.

`chef-solo` is then run with chef repository you have specified using the node
tag as a configuration target.
"
  (:use [pallet.utils
         :only [remote-sudo make-user *admin-user* default-public-key-path
                as-string]]
        [pallet.compute
         :only [node-has-tag? node-counts-by-tag boot-if-down]]
        [org.jclouds.compute
         :only [run-nodes destroy-node nodes tag running? compute-service? *compute*]]
        clojure.contrib.logging)
  (:import org.jclouds.compute.domain.OsFamily
           org.jclouds.compute.options.TemplateOptions
           org.jclouds.compute.domain.NodeMetadata))


(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is specified
  either as a user map, which can be created with make-user, or as an argument
  list that will be passed to make-user."
  [user & exprs]
  `(let [user# ~user]
     (binding [*admin-user* (if (vector? user#) (apply make-user user#) user#)]
       ~@exprs)))

(defn admin-user
  "Set the root binding for the admin user.
The user arg is a map as returned by make-user, or a username.
When passing a username the following options can be specified:
  :password
  :private-key-path
  :public-key-path
"
  [user & options]
  (alter-var-root
   #'*admin-user* #(identity %2) (if (string? user)
                                   (apply make-user user options)
                                   user)))

(def #^{:doc "Map from tag keyword to template specification.  Each template
specification is a vector of arguments for build-template."}
     *node-templates* {})

(defmacro with-node-templates
  "Set the template map"
  [map & body]
  `(binding [*node-templates* ~map]
     ~@body))

(defn build-node-template
  "Build a template for passing to jclouds run-nodes."
  ([compute options]
     (build-node-template compute options (default-public-key-path) nil))
  ([compute options public-key-path init-script]
     (debug (str "Init script\n" init-script))
     (let [options
           (if (and public-key-path (not (:authorize-public-key options)))
             (apply
              vector :authorize-public-key (slurp public-key-path) options)
             options)
           options
           (if (and init-script (not (:run-script options)))
             (apply vector :run-script (.getBytes init-script) options)
             options)]
    (apply org.jclouds.compute/build-template compute options))))

(defn node-template
  "Build the template for specified target node and compute context"
  ([compute target public-key-path init-script]
     (node-template compute target public-key-path init-script *node-templates*))
  ([compute target public-key-path init-script node-templates]
     {:pre [(keyword? target)]}
     (info (str "building node template for " target))
     (when public-key-path (info (str "  authorizing " public-key-path)))
     (when init-script (info (str "  using init script")))
     (let [options (target node-templates)
           init-script (if init-script (init-script target options))]
       (build-node-template
        compute
        options
        public-key-path
        init-script))))

(defn start-node
  "Convenience function for explicitly starting nodes."
  ([tag template] (start-node tag template *compute*))
  ([tag template compute]
     (run-nodes (as-string tag) 1 (build-node-template compute template) compute)))

(def #^{:doc "Default bootstrap option. A no-op."}
     bootstrap-none {:authorize-public-key nil :bootstrap-script nil})

(defn- bootstrap-script-fn
  [fns]
  (cond
   (nil? fns) (fn [tag template] nil)
   (or (vector? fns) (seq? fns))
   (fn [tag template] (apply str (interpose "\n" (map #(% tag template) fns))))
   :else fns))

(defn create-nodes
  "Create count nodes based on the template for tag. The boostrap argument
expects a map with :authorize-public-key and :bootstrap-script keys.  The
bootstrap-script value is expected tobe a function that produces a
script that is run with root privileges immediatly after first boot."
  ([tag count compute]
     (create-nodes tag count bootstrap-none compute))
  ([tag count bootstrap compute]
     {:pre [(keyword? tag)]}
     (info (str "Starting " count " nodes for " tag))
     (run-nodes (name tag) count
                (node-template
                 compute tag
                 (:authorize-public-key bootstrap)
                 (bootstrap-script-fn (:bootstrap-script bootstrap)))
                compute)))

(defn destroy-nodes-with-count [nodes tag count compute]
  (info (str "destroying " count " nodes with tag " tag))
  (dorun (map #(destroy-node % compute)
              (take count (filter (partial node-has-tag? tag) nodes)))))

(defn node-count-difference [node-map nodes]
  (merge-with - node-map
              (select-keys (node-counts-by-tag nodes) (keys node-map))))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  ([compute delta-map nodes]
     (adjust-node-counts compute delta-map nodes bootstrap-none))
  ([compute delta-map nodes bootstrap]
     (info (str "destroying excess nodes"))
     (doseq [node-count (filter #(neg? (second %)) delta-map)]
       (destroy-nodes-with-count
         nodes (first node-count) (- (second node-count)) compute))
     (info (str "adjust-node-counts starting new nodes"))
     (mapcat #(create-nodes (first %) (second %) bootstrap compute)
             (filter #(pos? (second %)) delta-map))))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  ([compute node-map]
     (converge-node-counts compute node-map bootstrap-none))
  ([compute node-map bootstrap]
     (let [nodes (nodes compute)]
       (boot-if-down compute nodes)     ; this needs improving
                                        ; should only reboot if required
       (adjust-node-counts compute (node-count-difference node-map nodes)
                           nodes bootstrap))))

(defn configure-nodes-none
  "A function for no configuration"
  [compute new-nodes] new-nodes)

(defn converge*
  [compute node-map bootstrap configure]
  (converge-node-counts compute node-map bootstrap)
  (let [tags (->> node-map keys (map name) set)]
    (configure compute (->> (nodes compute)
                         (filter running?)
                         (filter #(-> % tag tags))))))

;; We have some options for converging
;; Unneeded nodes can be just shut dowm, or could be destroyed.
;; That means we need to pass some options
(defn converge
  "Converge the existing compute resources with what is specified in node-map.
   Note that the non-bootstrap configuration is applied to all running nodes
   whose tags match keys in the node-map.
   Returns a sequence containing the node metadata for *new* nodes.

   Takes node-map ( bootstrap-fn configure-fn? )? compute-service?

   Nodes in excess of those specified in node-map are destroyed.  Conversely, if
   the node count for a given tag is too low, new nodes are started.  The image
   template for a given tag is looked up in *node-templates*, which can be bound
   using (with-node-templates ...)

   Templates specify a vector of features that should match a single image.  The
   keywords match those used in jclouds TemplateBuilder, adjusted for clojure
   conventions (so imageId becomes :image-id).  Values for the jclouds enums
   used in TemplateBuilder are recognised (eg. :ubuntu).

   bootstrap-fn is executed while logged in as the default image user, and is
   only executed when starting new images.  configure-fn is executed as the
   *admin-user* which can be bound using (with-admin-user ...), and is always
   executed."
  ([node-map & options]
     (let [fns (filter (partial instance? clojure.lang.IFn) options)
           bootstrap (or (first
                          (filter #(and (map? %)
                                        (:bootstrap-script %)) options))
                         bootstrap-none)
           configure (or (fnext fns) configure-nodes-none)
           compute (or (first (filter compute-service? options)) *compute*)]
       (converge* compute node-map bootstrap configure))))
