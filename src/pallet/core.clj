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
  (:require
   [pallet.blobstore :as blobstore]
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
   [clojure.contrib.logging :as logging]
   [clojure.contrib.map-utils :as map-utils])
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

(defn make-node
  "Create a node definition.  See defnode."
  [name image & {:as phase-map}]
  {:pre [(or (nil? image) (map? image))]}
  {:tag (keyword name)
   :image image
   :phases phase-map})

(defn name-with-attributes
  "Modified version, of that found in contrib, to handle the image map."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
        [attr macro-args] (if (and (map? (first macro-args))
                                   (map? (first (next macro-args))))
                            [(first macro-args) (next macro-args)]
                            [{} macro-args])
        attr (if docstring
               (assoc attr :doc docstring)
               attr)
        attr (if (meta name)
               (conj (meta name) attr)
               attr)]
    [(with-meta name attr) macro-args]))

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

(defn add-os-family
  "Add the os family to the node-type if available from node."
  [request]
  (update-in
   request [:node-type :image :os-family]
   (fn ensure-os-family [f]
     (or (when-let [node (:target-node request)]
           (compute/node-os-family node))
         f))))

(defn add-target-id
  "Add the target-id to the request"
  [request]
  (update-in
   request [:target-id]
   (fn ensure-target-id [id]
     (or
      (when-let [node (:target-node request)] (keyword (.getId node)))
      id))))

(defn add-target-packager
  "Add the target packager to the request"
  [request]
  (update-in
   request [:target-packager]
   (fn ensure-target-packager [p]
     (or p (compute/packager (get-in request [:node-type :image]))))))

(defn add-target-keys
  "Add target keys on the way down"
  [handler]
  (fn [request]
    (handler
     (-> request
         add-os-family
         add-target-packager
         add-target-id))))

(defn show-target-keys
  "Middleware that is useful in debugging."
  [handler]
  (fn [request]
    (logging/info
     (format
      "TARGET KEYS :phase %s :target-id %s :tag %s :target-packager %s"
      (:phase request)
      (:target-id request)
      (:tag (:node-type request))
      (:target-packager request)))
    (handler request)))


(defn resource-invocations [request]
  {:pre [(:phase request)]}
  (if-let [f (some
              (:phase request)
              [(:phases (:node-type request)) (:phases request)])]
    (let [request ((utils/pipe add-target-keys identity) request)]
      (script/with-template [(-> request :node-type :image :os-family)
                             (-> request :target-packager)]
        (f request)))
    request))

(defn produce-init-script
  [request]
  {:pre [(get-in request [:node-type :image :os-family])]}
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
      (script/with-template [(-> request :node-type :image :os-family)
                             (-> request :target-packager)]
        (:cmds (f request)))
      "")))

(defn create-nodes
  "Create count nodes based on the template for tag. The boostrap argument
expects a map with :authorize-public-key and :bootstrap-script keys.  The
bootstrap-script value is expected tobe a function that produces a
script that is run with root privileges immediatly after first boot."
  [node-type count request]
  {:pre [(map? node-type)]}
  (logging/info (str "Starting " count " nodes for " (node-type :tag)))
  (let [request (compute/ensure-os-family
                 (:compute request)
                 (assoc request :node-type node-type))
        init-script (produce-init-script request)]
    (compute/run-nodes
     (:compute request)
     node-type
     count
     request
     init-script)))

(defn destroy-nodes-with-count
  "Destroys the specified number of nodes with the given tag.  Nodes are
   selected at random."
  [nodes tag destroy-count compute]
  (logging/info (str "destroying " destroy-count " nodes with tag " tag))
  (let [tag-nodes (filter (partial compute/node-has-tag? tag) nodes)]
    (if (= destroy-count (count tag-nodes))
      (jclouds/destroy-nodes-with-tag (name tag) compute)
      (doseq [node (take destroy-count tag-nodes)]
        (jclouds/destroy-node (.getId node) compute)))))

(defn node-count-difference
  "Find the difference between the required and actual node counts by tag."
  [node-map nodes]
  (let [node-counts (compute/node-counts-by-tag nodes)]
    (merge-with
     - node-map
     (into {} (map #(vector (first %) (get node-counts ((first %) :tag) 0))
                   node-map)))))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  [delta-map nodes request]
  (logging/trace (str "adjust-node-counts" delta-map))
  (logging/info (str "destroying excess nodes"))
  (doseq [node-count (filter #(neg? (second %)) delta-map)]
    (destroy-nodes-with-count
      nodes ((first node-count) :tag) (- (second node-count))
      (:compute request)))
  (logging/info (str "adjust-node-counts starting new nodes"))
  (mapcat #(create-nodes (first %) (second %) request)
          (filter #(pos? (second %)) delta-map)))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [node-map nodes request]
  (logging/info "converging nodes")
  (logging/trace (str "  " node-map))
  (compute/boot-if-down (:compute request) nodes) ; this needs improving
                                        ; should only reboot if required
  (let [nodes (filter compute/running? nodes)]
    (adjust-node-counts
     (node-count-difference node-map nodes)
     nodes
     request)))


(defn execute-with-ssh
  [{:keys [target-node] :as request}]
  (execute/ssh-cmds
   (assoc request
     :address (compute/node-address target-node)
     :ssh-port (compute/ssh-port target-node))))

(defn execute-with-local-sh
  "Middleware to execute on localhost with shell"
  [handler]
  (fn [{:as request}]
    (execute/local-sh-cmds request)))

(defn parameter-keys [node node-type]
  [:default
   (compute/packager (node-type :image))
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
    {:pre [handler
           (-> request :node-type :image :os-family)
           (-> request :target-packager)]}
    (handler
     (assoc request
       :commands (script/with-template
                   [(-> request :node-type :image :os-family)
                    (-> request :target-packager)]
                   (resource/produce-phase request))))))

(defn apply-phase-to-node
  "Apply a phase to a node request"
  [request]
  {:pre [(:target-node request)]}
  (let [middleware (:middleware request)]
    ((utils/pipe
      add-target-keys
      build-commands
      middleware
      execute-with-ssh)
     request)))

(defn wrap-no-exec
  "Middleware to report on the request, without executing"
  [_]
  (fn [request]
    (logging/info
     (format
      "Commands on node %s as user %s"
      (pr-str (:node request))
      (pr-str (:user request))))
    (letfn [(execute
             [cmd]
             (logging/info (format "Commands %s" cmd))
             cmd)]
      (resource/execute-commands request {:script/bash execute
                                          :transfer/to-local (fn [& _])
                                          :transfer/from-local (fn [& _])}))))

(defn wrap-local-exec
  "Middleware to execute only local functions"
  [_]
  (fn [request]
    (letfn [(execute [cmd] nil)]
      (resource/execute-commands request {:script/bash execute
                                          :transfer/to-local (fn [& _])
                                          :transfer/from-local (fn [& _])}))))

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
  [nodes request]
  (logging/info
   (format
    "apply-phase %s for %s with %d nodes"
    (:phase request) (:tag (:node-type request)) (count nodes)))
  (reduce
   (fn apply-phase-accumulate [request [result req :as arg]]
     (let [param-keys [:parameters :host (:target-id req)]]
       (->
        request
        (assoc-in [:results (:target-id req) (:phase req)] result)
        (update-in
         param-keys
         (fn [p]
           (map-utils/deep-merge-with
            (fn [x y] (or y x)) p (get-in req param-keys)))))))
   request
   (for [node nodes]
     (apply-phase-to-node
      (assoc request :target-node node)))))

(defn nodes-in-map
  "Return nodes with tags corresponding to the keys in node-map"
  [node-map nodes]
  (let [tags (->> node-map keys (map :tag) (map name) set)]
    (->> nodes (filter compute/running?) (filter #(-> % compute/tag tags)))))

(defn filter-nodes-with-tag
  "Return nodes with the given tag"
  [nodes with-tag]
  (filter #(= (name with-tag) (compute/tag %)) nodes))

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
  (some #(= (compute/tag node) (name (% :tag))) node-types))

(defn nodes-for-type
  "Return the nodes that have a tag that matches one of the node types"
  [nodes node-type]
  (let [tag-string (name (node-type :tag))]
    (filter #(= tag-string (compute/tag %)) nodes)))

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
   #(resource-invocations
     (assoc %1 :target-node %2 :target-id (keyword (.getId %2))))
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
  [all-nodes target-node-map all-node-map phases request]
  (logging/trace (format "lift-nodes phases %s" (vec phases)))
  (let [target-nodes (filter compute/running?
                             (apply concat (vals target-node-map)))
        all-nodes (or all-nodes target-nodes) ; Target node map may contain
                                        ; unmanged nodes
        [request phases] (identify-anonymous-phases request phases)
        request (assoc request
                  :all-nodes all-nodes
                  :target-nodes target-nodes)
        request (invoke-phases
                 request (ensure-configure-phase phases) all-node-map)]
    (->
     (reduce
      (fn lift-nodes-reduce-result [request req]
        (->
         request
         (update-in
          [:results]
          #(map-utils/deep-merge-with
            (fn [x y] (or y x)) (or % {}) (:results req)))
         (update-in
          [:parameters]
          #(map-utils/deep-merge-with
            (fn [x y] (or y x)) % (:parameters req)))))
      request
      (for [phase (resource/phase-list phases)
            [node-type tag-nodes] target-node-map]
        (apply-phase
         tag-nodes
         (assoc request :phase phase :node-type node-type))))
     (dissoc :node-type :target-node :target-nodes :target-id :phase
             :invocations :user))))

(defn lift*
  [node-set all-node-set phases request]
  (logging/trace (format "lift* phases %s" (vec phases)))
  (let [nodes (or
               (:all-nodes request)
               (when (:compute request)
                 (logging/info "retrieving nodes")
                 (filter
                  compute/running?
                  (compute/nodes-with-details (:compute request)))))
        target-node-map (nodes-in-set node-set (:prefix request) nodes)
        all-node-map (or (and all-node-set
                              (nodes-in-set all-node-set nil nodes))
                         target-node-map)]
    (lift-nodes nodes target-node-map all-node-map phases request)))

(defn converge*
  "Converge the node counts of each tag in node-map, executing each of the
   configuration phases on all the tags in node-map. Th phase-functions are
   also executed, but not applied, for any other nodes in all-node-set"
  [node-map all-node-set phases request]
  {:pre [(map? node-map)]}
  (logging/trace (format "converge* %s %s" node-map phases))
  (logging/info "retrieving nodes")
  (let [node-map (add-prefix-to-node-map (:prefix request) node-map)
        nodes (compute/nodes-with-details (:compute request))]
    (converge-node-counts node-map nodes request)
    (let [nodes (filter
                 compute/running?
                 (compute/nodes-with-details (:compute request)))
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
       nodes target-node-map all-node-map phases request))))

(defmacro or-fn [& args]
  `(fn or-args [current#]
     (or current# ~@args)))

(defn- build-request-map
  "Build a request map from the given options."
  [{:as options}]
  (->
   options
   (update-in [:compute] compute/compute-from-options options)
   (update-in [:blobstore] blobstore/blobstore-from-options options)
   (update-in [:user] (or-fn utils/*admin-user*))
   (update-in [:middleware] (or-fn *middleware*))))

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
  [node-map & {:keys [compute phase prefix middleware all-node-set]
               :as options}]
  (converge*
   node-map all-node-set
   (if (sequential? phase) phase (if phase [phase] nil))
   (build-request-map (dissoc options :all-node-set :phase))))


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

   Options:
    :compute         a jclouds compute service
    :compute-service a map of :provider, :identity, :credential, and
                     optionally :extensions for constructing a jclouds compute
                     service.
    :phase           a phase keyword, phase function, or sequence of these
    :middleware      the middleware to apply to the configuration pipeline
    :prefix          a prefix for the tag names
    :user            the admin-user on the nodes
"
  [node-set & {:keys [compute phase prefix middleware all-node-set]
               :as options}]
  (lift*
   node-set all-node-set
   (if (sequential? phase) phase (if phase [phase] nil))
   (build-request-map (dissoc options :all-node-set :phase))))
