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
           as-string *file-transfers*]]
   [pallet.resource
    :only [produce-phases defphases with-init-resources]]
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

(. System setProperty "http.agent"
   (str "Pallet " (System/getProperty "pallet.version")))

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
  :public-key-path"
  [user & options]
  (alter-var-root
   #'*admin-user* #(identity %2) (if (string? user)
                                   (apply make-user user options)
                                   user)))

(defmacro with-no-compute-service
  "Bind a null provider, for use when accessing local vms."
  [& body]
  `(binding [*compute* nil]
     ~@body))

(defmacro defnode
  "Define a node type.  The name is used for the node tag. Options are:

   :image defines the image selector template.  This is a vector of keyword or
          keyword value pairs that are used to filter the image list to select
          an image.
   :configure defines the configuration of the node."
  [name image & options]
  (let [[name options] (name-with-attributes name options)]
    `(def ~name
          (apply hash-map
                 (vector :tag (keyword (name '~name))
                         :image ~image
                         :phases (defphases ~@options))))))

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
  (trace (str "adjust-node-counts" delta-map))
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
  ([compute node-map] (converge-node-counts compute node-map (nodes compute)))
  ([compute node-map nodes]
     (info "converging nodes")
     (trace (str "  " node-map))
     (boot-if-down compute nodes)       ; this needs improving
                                        ; should only reboot if required
     (let [nodes (filter running? nodes)]
       (adjust-node-counts
        compute
        (node-count-difference node-map nodes)
        nodes))))

(defn apply-phases-to-node
  "Apply a list of phases to a sequence of nodes"
  [compute node-type node phases user]
  (info (str "apply-phases-to-node " (tag node)))
  (let [phases (if (seq phases) phases [:configure])
        port (ssh-port node)
        options (if port [:port port] [])]
    (if node-type
      (doseq [phase phases]
        (with-init-resources nil
          (binding [*file-transfers* {}]
            (when-let [script (produce-phases [phase] node node-type
                                (node-type :phases))]
              (info script)
              (apply execute-script script node user options)))))
      (error (str "Could not find node type for node " (tag node))))))

(defn apply-phases
  "Apply a list of phases to a sequence of nodes"
  ([compute node-type nodes phases]
     (apply-phases compute node-type nodes phases *admin-user*))
  ([compute node-type nodes phases user]
     (trace (str "apply-phases for " (node-type :tag)
                 " " (count nodes) " nodes"))
     (doseq [node nodes]
       (apply-phases-to-node compute node-type node phases user))))

(defn nodes-in-map
  "Return nodes with tags corresponding to the keys in node-map"
  [node-map nodes]
  (let [tags (->> node-map keys (map :tag) (map name) set)]
    (->> nodes (filter running?) (filter #(-> % tag tags)))))

(defn filter-nodes-with-tag
  "Return nodes with the given tag"
  [nodes with-tag]
  (filter #(= (name with-tag) (tag %)) nodes))

(defn add-prefix-to-node-type
  [prefix node-type]
  (update-in node-type [:tag]
             (fn [tag] (keyword (str prefix (name tag))))))

(defn add-prefix-to-node-map [prefix node-map]
  (zipmap
   (map (partial add-prefix-to-node-type prefix) (keys node-map))
   (vals node-map)))

(defn ensure-configure-phase [phases]
  (if (some #{:configure} phases)
    phases
    (concat [:configure] phases)))

(defn converge*
  [compute prefix node-map phases]
  {:pre [(map? node-map)]}
  (trace (str "converge*  " node-map))
  (let [node-map (add-prefix-to-node-map prefix node-map)]
    (converge-node-counts compute node-map)
    (let [nodes (nodes compute)
          phases (ensure-configure-phase phases)]
      (doseq [node-type (keys node-map)]
        (apply-phases
         compute
         node-type
         (filter-nodes-with-tag nodes (node-type :tag))
         phases)))))

(defn node-in-types?
  "Predicate for matching a node belonging to a set of node types"
  [node-types node]
  (some #(= (tag node) (name (% :tag))) node-types))

(defn nodes-for-type
  "Return the nodes that have a tag that matches one of the node types"
  [nodes node-type]
  (let [tag-string (name (node-type :tag))]
    (filter #(= tag-string (tag %)) nodes)))

(defn node-type?
  "Prdicate for testing if argument is node-type."
  [x]
  (and (map? x) (x :tag) (x :image) true))

(defn nodes-in-set
  "Build a map of nodes for the given node-set. A node set can be a node
  type, a sequence of node types, a node node-typ vector, or a sequence of nodes.
  e.g
     [node-type1 node-type2 {node-type #{node1 node2}}]

  The return value is a map of node-type -> node sequence."
  ([node-set prefix] (nodes-in-set node-set prefix *compute*))
  ([node-set prefix compute]
     (nodes-in-set node-set prefix compute (if compute (nodes compute))))
  ([node-set prefix compute nodes]
     (letfn [(ensure-set [x] (if (set? x) x #{x}))
             (ensure-set-values
              [m]
              (zipmap (keys m) (map ensure-set (vals m))))]
       (cond
        (and (map? node-set) (not (node-type? node-set)))
        (ensure-set-values (add-prefix-to-node-map prefix node-set))
        (node-type? node-set)
        (let [node-type (add-prefix-to-node-type prefix node-set )]
          {node-type (set (nodes-for-type nodes node-type))})
        :else (reduce
               #(merge-with concat %) {}
               (apply #(nodes-in-set % compute nodes) node-set))))))

(defn lift*
  [compute prefix node-set phases]
  (doseq [[node-type nodes] (nodes-in-set node-set prefix compute)]
    (apply-phases
     compute
     node-type
     (filter running? nodes)
     phases)))

(defn compute-service-and-options
  "Extract the compute service form a vector of options, returning the bound
  compute service if none specified."
  [arg options]
  (let [prefix (if (string? arg) arg)
        node-spec (if prefix (first options) arg)
        options (if prefix (rest options) options)
        compute (or (first (filter compute-service? options)) *compute*)]
    [compute prefix node-spec (remove #{compute} options)]))

(defn converge
  "Converge the existing compute resources with the counts specified in
   node-map.  The compute service may be supplied as an option, otherwise the
   bound compute-service is used.

   This applies the bootstrap phase to all new nodes, and the configure phase to
   all running nodes whose tag matches a key in the node map.  Additional phases
   can also be specified in the options, and will be applied to all matching
   nodes.  The :configure phase is always applied, as the first (post bootstrap)
   phase.  You can change the order in which the phases are applied by
   explicitly listing them.

   An optional tag prefix may be specified before the node-map."
  [node-map & options]
  (apply converge* (compute-service-and-options node-map options)))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified
   phases.  The compute service may be supplied as an option, otherwise the
   bound compute-service is used.  The configure phase is applied by default
   unless other phases are specified.

   node-set can be a node type, a sequence of node types, or a map
            of node type to nodes. Examples:
              [node-type1 node-type2 {node-type #{node1 node2}}]
              node-type
              {node-type #{node1 node2}}

   options can also be keywords specifying the phases to apply, or an immediate
   phase specified with the phase macro, or a function that will be called with
   each matching node.

   An optional tag prefix may be specified before the node-set."
  [node-set & options]
  (apply lift* (compute-service-and-options node-set options)))
