(ns pallet.api
  "# Pallet API

"
  (:require
   [pallet.compute :as compute]
   [pallet.configure :as configure]
   [pallet.core.operations :as ops])
  (:use
   [pallet.algo.fsmop :only [operate]]
   [pallet.environment :only [merge-environments]]
   [pallet.map-merge :only [merge-keys]]
   [pallet.monad :only [session-pipeline]]
   [pallet.thread-expr :only [when->]]
   [pallet.utils :only [apply-map]]))


;;; ## Domain Model

(def ^{:doc "Vector of keywords recognised by node-spec"
       :private true}
  node-spec-keys [:image :hardware :location :network])

(defn node-spec
  "Create a node-spec.

   Defines the compute image and hardware selector template.

   This is used to filter a cloud provider's image and hardware list to select
   an image and hardware for nodes created for this node-spec.

   :image     a map describing a predicate for matching an image:
              os-family os-name-matches os-version-matches
              os-description-matches os-64-bit
              image-version-matches image-name-matches
              image-description-matches image-id

   :location  a map describing a predicate for matching location:
              location-id
   :hardware  a map describing a predicate for matching harware:
              min-cores min-ram smallest fastest biggest architecture
              hardware-id
   :network   a map for network connectivity options:
              inbound-ports
   :qos       a map for quality of service options:
              spot-price enable-monitoring"
  [& {:keys [image hardware location network qos] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  options)

(def
  ^{:doc
    "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-comp
   :roles :union})

(defn- merge-specs
  "Merge specs, using comp for :phases"
  [algorithms a b]
  (merge-keys algorithms a b))

(defn extend-specs
  "Merge in the inherited specs"
  ([spec inherits algorithms]
     (if inherits
       (merge-specs
        algorithms
        (if (map? inherits)
          inherits
          (reduce (partial merge-specs algorithms) inherits))
        spec)
       spec))
  ([spec inherits]
     (extend-specs spec inherits merge-spec-algorithm)))

(defn server-spec
  "Create a server-spec.

   - :phases a hash-map used to define phases. Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node
   - :extends        takes a server-spec, or sequence thereof, and is used to
                     inherit phases, etc.
   - :roles          defines a sequence of roles for the server-spec
   - :node-spec      default node-spec for this server-spec
   - :packager       override the choice of packager to use"
  [& {:keys [phases packager node-spec extends roles]
      :as options}]
  (->
   node-spec
   (merge options)
   (when-> roles
       (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
   (extend-specs extends)
   (dissoc :extends :node-spec)))

(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to it's node-spec

   - :extends  specify a server-spec, a group-spec, or sequence thereof
               and is used to inherit phases, etc.

   - :phases used to define phases. Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node.

   - :count    specify the target number of nodes for this node-spec
   - :packager override the choice of packager to use
   - :node-spec      default node-spec for this server-spec"
  [name
   & {:keys [extends count image phases packager node-spec roles] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (->
   node-spec
   (merge options)
   (when-> roles
       (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
   (extend-specs extends)
   (dissoc :extends :node-spec)
   (assoc :group-name (keyword name))))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn expand-group-spec-with-counts
  "Expand a converge node spec into its groups"
  ([node-set spec-count]
     (letfn [(*1 [x y] (* (or x 1) y))
             (scale-spec [spec factor]
               (update-in spec [:count] *1 factor))
             (set-spec [node-spec]
               (mapcat
                (fn [[node-spec spec-count]]
                  (if-let [groups (:groups node-spec)]
                    (expand-group-spec-with-counts groups spec-count)
                    [(assoc node-spec :count spec-count)]))
                node-set))]
       (cond
        (sequential? node-set) (mapcat
                                #(expand-group-spec-with-counts % spec-count)
                                node-set)
        (map? node-set) (if-let [groups (:groups node-set)]
                          (let [spec (scale-spec node-set spec-count)]
                            (mapcat
                             #(expand-group-spec-with-counts % (:count spec))
                             groups))
                          (if (:group-name node-set)
                            [(scale-spec node-set spec-count)]
                            (set-spec node-spec)))
        :else [(scale-spec node-set spec-count)])))
  ([node-set] (expand-group-spec-with-counts node-set 1)))

(defn cluster-spec
  "Create a cluster-spec.

   `name` is used as a prefix for all groups in the cluster.

   - :groups    specify a sequence of groups that define the cluster

   - :extends   specify a server-spec, a group-spec, or sequence thereof
                for all groups in the cluster

   - :phases    define phases on all groups.

   - :node-spec default node-spec for the nodes in the cluster

   - :roles     roles for the group-spec"
  [cluster-name
   & {:keys [extends groups phases node-spec environment] :as options}]
  (->
   options
   (update-in [:groups]
              (fn [group-specs]
                (map
                 (fn [group-spec]
                   (->
                    node-spec
                    (merge (dissoc group-spec :phases))
                    (update-in
                     [:group-name]
                     #(keyword (str (name cluster-name) "-" (name %))))
                    (update-in
                     [:environment]
                     merge-environments environment)
                    (extend-specs extends)
                    (extend-specs [{:phases phases}])
                    (extend-specs [(select-keys group-spec [:phases])])))
                 (expand-group-spec-with-counts group-specs 1))))
   (dissoc :extends :node-spec)
   (assoc :cluster-cluster-name (keyword cluster-name))))

;;; ## Compute Service
;;;
;;; The compute service is used to communicate with the cloud provider
(defn compute-service
  "Returns a compute service object, used to perform actions on a cloud
  provider."
  [{:keys [config provider] :as options}]
  (if config
    (configure/compute-service config)
    (apply-map compute/compute-service provider (dissoc options provider))))

;;; ## Operations
;;;

(defn converge
  "Converge the existing compute resources with the counts specified in
   `group-spec->count`. New nodes are started, or nodes are destroyed
   to obtain the specified node counts.

   `group-spec->count` can be a map from group-spec to node count, or can be a
   sequence of group-specs containing a :count key.

   The compute service may be supplied as an option, otherwise the bound
   compute-service is used.


   This applies the bootstrap phase to all new nodes and the configure phase to
   all running nodes whose group-name matches a key in the node map.  Additional
   phases can also be specified in the options, and will be applied to all
   matching nodes.  The :configure phase is always applied, by default as the
   first (post bootstrap) phase.  You can change the order in which
   the :configure phase is applied by explicitly listing it.

   An optional group-name prefix may be specified. This will be used to modify
   the group-name for each group-spec, allowing you to build multiple discrete
   clusters from a single set of group-specs."
  [group-spec->count & {:keys [compute blobstore user phase prefix middleware
                               all-nodes all-node-set environment]
                        :as options}]
  (let [groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        phases (if (keyword phase) [phase] [:configure])
        settings-groups (concat groups all-node-set)
        environment (pallet.environment/environment compute)]
    (operate
     (ops/converge
      groups all-node-set phases compute environment {}))))

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
    :prefix          a prefix for the group-name names
    :user            the admin-user on the nodes"
  [node-set & {:keys [compute phase prefix middleware all-node-set environment]
               :as options}]
  (let [groups (if (map? node-set) [node-set] node-set)
        phases (if (keyword phase) [phase] [:configure])
        environment (pallet.environment/environment compute)
        plan-state {}]
    (operate
     (ops/lift groups all-node-set phases compute environment plan-state))))

;;; ### plan functions
(defmacro plan-fn
  "Create a phase function from a sequence of crate invocations with
   an ommited session parameter.

   eg. (plan-fn
         (file \"/some-file\")
         (file \"/other-file\"))

   which generates a function with a session argument, that is thread
   through the function calls. The example is thus equivalent to:

   (fn [session] (-> session
                   (file \"/some-file\")
                   (file \"/other-file\"))) "
  [& body]
  `(session-pipeline ~(gensym "a-plan-fn") {}
     ~@body))
