(ns #^{:author "Hugo Duncan"}
  pallet.core
"Pallet is a functional configuration management system, that can be used to
provision and manage configured compute nodes.

It uses a declaritive map for specifying the number of nodes with a given tag.
Each tag is used to look up a machine image template specification (in jclouds),
and to configuration information (in chef).  The converge function then
tries to bring you compute servers into alignment with your declared counts and
configurations.

The bootstrap process for new compute nodes installs a user with sudo
permissions, using the specified username and password. The installed user is
used to execute the chef cookbooks.

Once the nodes are bootstrapped, and fall all existing nodes
the configured node information is written to the \"compute-nodes\" cookbook
before chef is run, and this provides a :compute_nodes attribute.  The
compute-nodes cookbook is expected to exist in the site-cookbooks of the
chef-repository you specify with `with-chef-repository`.

"
  (:use
   [pallet.compute
    :only [node-has-tag? node-counts-by-tag boot-if-down compute-node?
           ssh-port]]
   [org.jclouds.compute
    :only [run-nodes destroy-node nodes-with-details tag running?
           compute-service? *compute*]]
   clojure.contrib.def)
  (:require
   [pallet.compute :as compute]
   [pallet.execute :as execute]
   [pallet.utils :as utils]
   [pallet.script :as script]
   [pallet.target :as target]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.string :as string]
   [org.jclouds.compute :as jclouds]
   [clojure.contrib.logging :as logging])
  (:import org.jclouds.compute.domain.OsFamily
           org.jclouds.compute.options.TemplateOptions
           org.jclouds.compute.domain.NodeMetadata))

(. System setProperty "http.agent"
   (str "Pallet " (System/getProperty "pallet.version")))

(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is specified
   either as pallet.utils.User record (see the pallet.utils/make-user
   convenience fn) or as an argument list that will be passed to make-user."
  [user & exprs]
  `(let [user# ~user]
     (binding [utils/*admin-user* (if (utils/user? user#)
                                    user#
                                    (apply utils/make-user user#))]
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
   #'utils/*admin-user*
   #(identity %2)
   (if (string? user)
     (apply utils/make-user user options)
     user)))

(defmacro with-no-compute-service
  "Bind a null provider, for use when accessing local vms."
  [& body]
  `(binding [*compute* nil]
     ~@body))

(defn make-node
  "Create a node definition.  See defnode."
  [name image & {:as phase-map}]
  {:tag (keyword name)
   :image image
   :phases phase-map})

(defmacro defnode
  "Define a node type.  The name is used for the node tag.

   image defines the image selector template.  This is a vector of keyword or
          keyword value pairs that are used to filter the image list to select
          an image.
   Options are used to define phases. Standard phases are:
     :bootstrap    run on first boot
     :configure    defines the configuration of the node."
  {:arglists ['(tag doc-str? attr-map? image & phasekw-phasefn-pairs)]}
  [tag & options]
  (let [[tag options] (name-with-attributes tag options)]
    `(def ~tag (make-node '~(name tag) ~@options))))

(defn resource-invocations [request]
  (if-let [f (some
              (:phase request)
              [(:phases (:node-type request)) (:phases request)])]
    (script/with-template (:image (:node-type request))
      (f request))
    request))

(defn produce-init-script
  [request]
  (let [cmds
        (resource/produce-phase
         (resource-invocations
          (assoc request :phase :bootstrap :target-id :bootstrap-id)))]
    (if-not (and (every? #(= :remote (:location %)) cmds) (>= 1 (count cmds)))
      (condition/raise
       :type :booststrap-contains-local-resources
       :message (format
                 "Bootstrap can not contain local resources %s"
                 (pr-str cmds))))
    (if-let [f (:f (first cmds))]
      (script/with-template (:image (:node-type request))
        (:cmds (f request)))
      "")))

(defn build-node-template-impl
  "Build a template for passing to jclouds run-nodes."
  ([compute options]
     (build-node-template-impl
      compute options (utils/default-public-key-path) nil))
  ([compute options public-key-path init-script]
     (logging/info (str "Options " options))
     (logging/info (str "Init script\n" init-script))
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
  [compute public-key-path request]
  {:pre [(map? (:node-type request))]}
  (logging/info
   (str "building node template for " (-> request :node-type :tag)))
  (when public-key-path (logging/info (str "  authorizing " public-key-path)))
  (let [options (-> request :node-type :image)
        init-script (produce-init-script request)]
    (when init-script (logging/info (str "  using init script")))
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
      (name (node-type :tag))
      1
      (build-node-template-impl compute (node-type :image))
      compute)))

(defn create-nodes
  "Create count nodes based on the template for tag. The boostrap argument
expects a map with :authorize-public-key and :bootstrap-script keys.  The
bootstrap-script value is expected tobe a function that produces a
script that is run with root privileges immediatly after first boot."
  [node count compute request]
  {:pre [(map? node)]}
  (logging/info (str "Starting " count " nodes for " (node :tag)))
  (run-nodes (-> node :tag name) count
             (build-node-template
              compute nil (assoc request :node-type node))
             compute))

(defn destroy-nodes-with-count
  "Destroys the specified number of nodes with the given tag.  Nodes are
   selected at random."
  [nodes tag count compute]
  (logging/info (str "destroying " count " nodes with tag " tag))
  (dorun (map #(destroy-node (.getId %) compute)
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
  [compute delta-map nodes request]
  (logging/trace (str "adjust-node-counts" delta-map))
  (logging/info (str "destroying excess nodes"))
  (doseq [node-count (filter #(neg? (second %)) delta-map)]
    (destroy-nodes-with-count
      nodes ((first node-count) :tag) (- (second node-count)) compute))
  (logging/info (str "adjust-node-counts starting new nodes"))
  (mapcat #(create-nodes (first %) (second %) compute request)
          (filter #(pos? (second %)) delta-map)))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [compute node-map nodes request]
  (logging/info "converging nodes")
  (logging/trace (str "  " node-map))
  (boot-if-down compute nodes)          ; this needs improving
                                        ; should only reboot if required
  (let [nodes (filter running? nodes)]
    (adjust-node-counts
     compute
     (node-count-difference node-map nodes)
     nodes
     request)))


(defn execute-with-ssh
  [{:keys [target-node] :as request}]
  (execute/ssh-cmds
   (assoc request
     :address (compute/node-address target-node)
     :ssh-port (compute/ssh-port target-node))))

(defn augment-template-from-node
  "Add the os family to the node-type if available from node"
  [handler]
  (fn [request]
    (handler
     (let [node (:target-node request)
           os-family (and node (compute/node-os-family node))]
       (if os-family
         (update-in request [:node-type :image] conj os-family)
         request)))))

(defn parameter-keys [node node-type]
  [:default
   (target/packager (node-type :image))
   (target/os-family (node-type :image))])

(defn with-target-parameters
  "Middleware to set parameters based on a specified parameter map"
  [handler parameters]
  (fn [request]
    (handler
     (assoc
         request :parameters
         (parameter/from-map
          parameters
          (parameter-keys (:target-node request) (:node-type request)))))))

(defn build-commands
  [handler]
  (fn [request]
    (handler
     (assoc request
       :commands (script/with-template (:image (:node-type request))
                   (resource/produce-phase request))))))

(defmacro pipe
  "Build a request processing pipeline from the specified forms"
  [& forms]
  (let [[middlewares etc] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares (reverse middlewares)
        [middlewares [x :as etc]]
          (if (seq etc)
            [middlewares etc]
            [(rest middlewares) (list (first middlewares))])
          handler x]
    (if (seq middlewares)
      `(-> ~handler ~@middlewares)
      handler)))


(defn apply-phase-to-node
  "Apply a phase to a node request"
  [compute wrapper-fn request]
  ((pipe
    augment-template-from-node
    build-commands
    wrapper-fn
    execute-with-ssh)
   request))

(defmacro with-local-exec
  "Run only local commands"
  [& body]
  `(binding [compute/execute-cmds (fn [commands# & _#]
                                    (utils/local-cmds commands#))]
     ~@body))


(defn no-exec
  [commands node user options]
  (logging/info
   (format "Commands %s on node %s as user %s with options %s"
           (pr-str commands)
           (pr-str node)
           (pr-str user)
           (pr-str options))))

(defmacro with-no-exec
  "Log commands, but do not run anything"
  [& body]
  `(binding [compute/execute-cmds no-exec]
     ~@body))

(defn wrap-no-exec
  "Middleware to report on the request, without executing"
  [_]
  (fn [request]
    (logging/info
     (format
      "Commands on node %s as user %s"
      (pr-str (:node request))
      (pr-str (:user request))))
    (letfn [(execute [cmd] (logging/info (format "Commands %s" cmd)))]
      (resource/execute-commands request execute))))

(defn wrap-local-exec
  "Middleware to execute only local functions"
  [_]
  (fn [request]
    (letfn [(execute [cmd] nil)]
      (resource/execute-commands request execute))))

(defn wrap-with-user-credentials
  [handler]
  (fn [request]
    (execute/possibly-add-identity
     (execute/default-agent)
     (:private-key-path (:user request))
     (:passphrase (:user request)))
    (handler request)))

(def *middleware* wrap-with-user-credentials)

(defmacro with-middleware
  "Wrap node execution in the given middleware. A middleware is a function of
   one argument (a handler function, that is the next middleware to call) and
   returns a dunction of one argument (the request map).  Middleware can be
   composed with the pipe macro."
  [f & body]
  `(binding [*middleware* ~f]
     ~@body))

(defn apply-phase
  "Apply a phase to a sequence of nodes"
  [compute node-execution-wrapper nodes request]
  (logging/info
   (format
    "apply-phase %s for %s with %d nodes"
    (:phase request) (:tag (:node-type request)) (count nodes)))
  (doseq [node nodes]
    (apply-phase-to-node
     compute node-execution-wrapper
     (assoc request :target-node node :target-id (keyword (.getId node))))))

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
  "Build a map of nodes for the given node-set. A node set can be a node type, a
   sequence of node types, a node node-typ vector, or a sequence of nodes.
     e.g [node-type1 node-type2 {node-type #{node1 node2}}]

  The return value is a map of node-type -> node sequence."
  [node-set prefix nodes]
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
            #(merge-with concat %1 %2) {}
            (map #(nodes-in-set % prefix nodes) node-set)))))

(defn identify-anonymous-phases
  [request phases]
  (reduce #(if (keyword? %2)
             [(first %1)
              (conj (second %1) %2)]
             (let [phase (keyword (name (gensym "phase")))]
               [(assoc-in (first %1) [:phases phase] %2)
                (conj (second %1) phase)])) [request []] phases))

(defn invoke-for-nodes
  "Build an invocation map for specified nodes."
  [request nodes]
  (reduce
   #(resource-invocations (assoc %1
                            :target-node %2
                            :target-id (keyword (.getId %2))))
   request nodes))

(defn invoke-for-node-type
  "Build an invocation map for specified node-type map."
  [request node-map]
  (reduce
   #(invoke-for-nodes (assoc %1 :node-type (first %2)) (second %2))
   request node-map))

(defn invoke-phases
  "Build an invocation map for specified phases and nodes.
   This allows configuration to be accumulated in the request parameters."
  [request phases node-map]
  (reduce #(invoke-for-node-type (assoc %1 :phase %2) node-map) request phases))

(defn lift-nodes
  "Lift nodes in target-node-map for the specified phases."
  [compute all-nodes target-node-map all-node-map
   phases execution-wrapper request]
  (let [target-nodes (filter running? (apply concat (vals target-node-map)))
        all-nodes (or all-nodes target-nodes) ; Target node map may contain
                                        ; unmanged nodes
        [request phases] (identify-anonymous-phases request phases)
        request (assoc request
                  :all-nodes all-nodes
                  :target-nodes target-nodes)
        request (invoke-phases request phases all-node-map)]
    (doseq [phase (resource/phase-list phases)
            [node-type tag-nodes] target-node-map]
      (apply-phase
       compute
       execution-wrapper
       tag-nodes
       (assoc request :phase phase :node-type node-type)))))

(defn lift*
  [compute prefix node-set all-node-set phases request middleware]
  (let [nodes (if compute (filter running? (nodes-with-details compute)))
        target-node-map (nodes-in-set node-set prefix nodes)
        all-node-map (or (and all-node-set
                              (nodes-in-set all-node-set nil nodes))
                         target-node-map)]
    (lift-nodes
     compute nodes target-node-map all-node-map
     phases middleware request)
    nodes))

(defn converge*
  "Converge the node counts of each tag in node-map, executing each of the
   configuration phases on all the tags in node-map. Th phase-functions are
   also executed, but not applied, for any other nodes in all-node-set"
  [compute prefix node-map all-node-set phases request middleware]
  {:pre [(map? node-map)]}
  (logging/trace (str "converge* " node-map))
  (let [node-map (add-prefix-to-node-map prefix node-map)
        nodes (nodes-with-details compute)]
    (converge-node-counts compute node-map nodes request)
    (let [nodes (filter running? (nodes-with-details compute))
          tag-groups (group-by #(keyword (.getTag %)) nodes)
          target-node-map (into
                           {}
                           (map
                            #(vector % ((:tag %) tag-groups))
                            (keys node-map)))
          all-node-map (or (and all-node-set
                                (nodes-in-set all-node-set nil nodes))
                           target-node-map)
          phases (ensure-configure-phase phases)]
      (lift-nodes
       compute nodes target-node-map all-node-map
       phases middleware request)
      nodes)))

(defn compute-service-and-options
  "Extract the compute service and user form a vector of options, returning the
   bound compute service if none specified."
  [arg options]
  (let [[prefix options] (if (string? arg)
                           [arg options]
                           [nil (concat [arg] options)])
        [node-spec options] [(first options) (rest options)]
        compute (or (first (filter compute-service? options)) *compute*)
        user (or (first (filter utils/user? options)) utils/*admin-user*)
        options (remove #{compute user} options)
        [node-map options] (if (map? (first options))
                             [(first options) (rest options)]
                             [nil options])]
    [compute prefix node-spec node-map options {:user user} *middleware*]))

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
