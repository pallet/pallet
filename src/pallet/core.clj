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
  (:use
   [pallet.utils
    :only [remote-sudo make-user *admin-user* default-public-key-path
           as-string]]
   [pallet.resource
    :only [produce-phases defphases]]
   [pallet.compute
    :only [node-has-tag? node-counts-by-tag boot-if-down compute-node?
           execute-script ssh-port]]
   [org.jclouds.compute
    :only [run-nodes destroy-node nodes tag running? compute-service? *compute*]]
   clojure.contrib.logging
   clojure.contrib.def)
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

(defvar- node-types (atom {}) "Enable lookup from tag to node type")

(defn add-node-type
  "Add a node type to the tag lookup map"
  [node] (swap! node-types merge {(node :tag) node}))

(defn node-type
  "Return the node type definition that matches the tag of the specified node."
  [node]
  (@node-types (-> node tag keyword)))

(defmacro defnode
  "Define a node type.  The name is used for the node tag. Options are:

   :image defines the image selector template.  This is a vector of keyword or
          keyword value pairs that are used to filter the image list to select
          an image.
   :configure defines the configuration of the node."
  [name & options]
  (let [[name options] (name-with-attributes name options)
        opts (apply hash-map options)]
    `(do
       (def ~name
            (apply hash-map
                   (vector :tag (keyword (name '~name))
                           :image ~(opts :image)
                           :phases (defphases ~@options))))
       (add-node-type ~name))))

(defn build-node-template-impl
  "Build a template for passing to jclouds run-nodes."
  ([compute options]
     (build-node-template-impl compute options (default-public-key-path) nil))
  ([compute options public-key-path init-script]
     (info (str "Options " options))
     (info (str "Init script\n" init-script))
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

(defn build-node-template
  "Build the template for specified target node and compute context"
  [compute target public-key-path]
  {:pre [(map? target)]}
  (info (str "building node template for " (target :tag)))
  (when public-key-path (info (str "  authorizing " public-key-path)))
  (let [options (target :image)
        init-script (produce-phases
                     [:bootstrap] (target :tag) options (target :phases))]
    (when init-script (info (str "  using init script")))
    (build-node-template-impl
     compute
     options
     public-key-path
     init-script)))

(defn start-node
  "Convenience function for explicitly starting nodes."
  ([node-type] (start-node node-type *compute*))
  ([node-type compute]
     (run-nodes
      (as-string (node-type :tag))
      1
      (build-node-template-impl compute (node-type :image))
      compute)))

(defn create-nodes
  "Create count nodes based on the template for tag. The boostrap argument
expects a map with :authorize-public-key and :bootstrap-script keys.  The
bootstrap-script value is expected tobe a function that produces a
script that is run with root privileges immediatly after first boot."
  [node count compute]
  {:pre [(map? node)]}
  (info (str "Starting " count " nodes for " (node :tag)))
  (run-nodes (->> node (:tag) (name)) count
             (build-node-template
              compute node
              nil)
             compute))

(defn destroy-nodes-with-count
  "Destroys the specified number of nodes with the given tag.  Nodes are
   selected at random."
  [nodes tag count compute]
  (info (str "destroying " count " nodes with tag " tag))
  (dorun (map #(destroy-node % compute)
              (take count (filter (partial node-has-tag? tag) nodes)))))

(defn node-count-difference
  "Find the difference between the required and actual node counts by tag."
  [node-map nodes]
  (let [node-counts (node-counts-by-tag nodes)]
    (merge-with
     - node-map
     (into {} (map #(vector (first %) (get node-counts ((first %) :tag) 0))
                   node-map)))))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  [compute delta-map nodes]
  (info (str "destroying excess nodes"))
  (doseq [node-count (filter #(neg? (second %)) delta-map)]
    (destroy-nodes-with-count
      nodes ((first node-count) :tag) (- (second node-count)) compute))
  (info (str "adjust-node-counts starting new nodes"))
  (mapcat #(create-nodes (first %) (second %) compute)
          (filter #(pos? (second %)) delta-map)))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [compute node-map]
  (info "converging nodes")
  (let [nodes (nodes compute)]
    (boot-if-down compute nodes)        ; this needs improving
                                        ; should only reboot if required
    (adjust-node-counts
     compute (node-count-difference node-map nodes) nodes)))

(defn apply-phases-to-node
  "Apply a list of phases to a sequence of nodes"
  [compute node phases user]
  (info "apply-phases-to-node")
  (let [node-info (node-type node)
        phases (if (seq phases) phases [:configure])
        port (ssh-port node)
        options (if port [:port port] [])]
    (doseq [phase phases]
      (when-let [script (produce-phases [phase] (tag node) (node-info :image)
                                        (node-info :phases))]
        (info script)
        (apply execute-script script node user options)))))

(defn apply-phases
  "Apply a list of phases to a sequence of nodes"
  ([compute nodes phases] (apply-phases compute nodes phases *admin-user*))
  ([compute nodes phases user]
     (info "apply-phases")
     (doseq [node nodes]
       (apply-phases-to-node compute node phases user))))

(defn nodes-in-map
  "Return nodes with tags corresponding to the keys in node-map"
  [node-map nodes]
  (let [tags (->> node-map keys (map :tag) (map name) set)]
    (->> nodes (filter running?) (filter #(-> % tag tags)))))

(defn converge*
  [compute node-map phases]
  (converge-node-counts compute node-map)
  (apply-phases
   compute
   (nodes-in-map node-map (nodes compute))
   (if (some #{:configure} phases)
     phases
     (concat [:configure] phases))))

(defn lift*
  [compute nodes phases]
  (apply-phases
     compute
     (->> nodes (filter running?))
     phases))

(defn node-in-types?
  "Predicate for matching a node belonging to a set of node types"
  [node-types node]
  (some #(= (tag node) (name (% :tag))) node-types))

(defn nodes-for-types
  "Return the nodes that hav a tag that matches one of the node types"
  [nodes node-types]
  (let [tags (set (map #(name (% :tag)) node-types))]
    (filter #(tags (tag %)) nodes)))

(defn nodes-in-set
  "Build a sequence of nodes for the given node-set. A node set can be a node
  type, a sequence of node types, a node, or a sequence of nodes."
  ([node-set] (nodes-in-set node-set *compute*))
  ([node-set compute]
     (cond
      (compute-node? node-set) [node-set]
      (and (not (map? node-set))
           (or (seq node-set) (vector? node-set) (set? node-set)))
      (if (every? compute-node? node-set)
        node-set
        (nodes-for-types (nodes compute) node-set))
      :else (nodes-for-types (nodes compute) [node-set]))))

(defn compute-service-and-options
  "Extract the compute service form a vector of options, returning the bound
  compute service if none specified."
  [options]
  (let [compute (or (first (filter compute-service? options)) *compute*)]
    [compute (remove #{compute} options)]))

(defn converge
  "Converge the existing compute resources with the counts specified in node-map.
   The compute service may be supplied as an option, otherwise the bound
   compute-service is used.

   This applies the bootstrap phase to all new nodes, and the configure phase to
   all running nodes whose tag matches a key in the node map.  Additional phases
   can also be specified in the options, and will be applied to all matching
   nodes.  The :configure phase is always applied, as the first (post bootstrap)
   phase.  You can change the order in which the phases are applied by
   explicitly listing them."
  ([node-map & options]
     (let [[compute phases] (compute-service-and-options options)]
       (converge* compute node-map phases))))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified phases.
   The compute service may be supplied as an option, otherwise the bound
   compute-service is used.  The configure phase is applied by default, unless
   other phases are specified.

   node-set can be a node type, a sequence of node types, a node, or a sequence
            of nodes.

   options can also be keywords specifying the phases to apply, or an immediate
           phase specified with the phase macro, or a function that will be
           called with each matching node."
  [node-set & options]
  (let [[compute phases] (compute-service-and-options options)]
    (lift* compute (nodes-in-set node-set compute) phases)))
