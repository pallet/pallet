(ns pallet.api
  "# Pallet API"
  (:require
   [clojure.java.io :refer [resource input-stream]]
   [clojure.set :refer [union]]
   [clojure.string :refer [blank?]]
   [clojure.pprint :refer [print-table]]
   [pallet.compute :as compute]
   [pallet.configure :as configure]
   [pallet.contracts :refer [check-converge-options check-group-spec
                             check-lift-options check-node-spec
                             check-server-spec check-user]]
   [pallet.core.user :as user]
   [pallet.core.operations :as ops]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.core.api-impl
    :only [merge-specs merge-spec-algorithm node-has-group-name?]]
   [pallet.core.session :only [session-context]]
   [pallet.crate :only [phase-context]]
   [pallet.algo.fsmop :only [dofsm operate result succeed]]
   [pallet.environment :only [group-with-environment merge-environments]]
   [pallet.node :only [node? node-map]]
   [pallet.plugin :only [load-plugins]]
   [pallet.thread-expr :only [when->]]
   [pallet.utils :only [apply-map]]))


;;; ## Pallet version
(let [v (atom nil)
      properties-path "META-INF/maven/com.palletops/pallet/pom.properties"]
  (defn version
    "Returns the pallet version."
    []
    (or
     @v
     (if-let [path (resource properties-path)]
       (with-open [in (input-stream path)]
         (let [properties (doto (java.util.Properties.) (.load in))]
           {:version (.getProperty properties "version")
            :revision (.getProperty properties "revision")}))
       {:version :unknown :revision :unknown}))))

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
  (check-node-spec (vary-meta options assoc :type ::node-spec)))

(defn extend-specs
  "Merge in the inherited specs"
  ([spec inherits algorithms]
     (if inherits
       (merge-specs
        algorithms
        (if (map? inherits)
          inherits
          (reduce #(merge-specs algorithms %1 %2) {} inherits))
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
   - :packager       override the choice of packager to use

For a given phase, inherited phase functions are run first, in the order
specified in the `:extends` argument."
  [& {:keys [phases packager node-spec extends roles]
      :as options}]
  (check-server-spec
   (->
    node-spec
    (or node-spec {})                    ; ensure we have a map and not nil
    (merge options)
    (when-> roles
            (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
    (extend-specs extends)
    (dissoc :extends :node-spec)
    (vary-meta assoc :type ::server-spec))))

(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to it's node-spec

   - :extends  specify a server-spec, a group-spec, or sequence thereof
               and is used to inherit phases, etc.

   - :phases      used to define phases. Standard phases are:
   - :bootstrap   run on first boot of a new node
   - :configure   defines the configuration of the node.

   - :count          specify the target number of nodes for this node-spec
   - :packager       override the choice of packager to use
   - :node-spec      default node-spec for this group-spec
   - :node-filter    a predicate to test if a node is a member of this group."
  [name
   & {:keys [extends count image phases packager node-spec roles node-filter]
      :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (let [group-name (keyword (clojure.core/name name))]
    (check-group-spec
     (->
      node-spec
      (merge options)
      (update-in [:node-filter] #(or % (node-has-group-name? group-name)))
      (when-> roles
              (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
      (extend-specs extends)
      (dissoc :extends :node-spec)
      (assoc :group-name group-name)
      (vary-meta assoc :type ::group-spec)))))

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

   - :roles     roles for all group-specs in the cluster"
  [cluster-name
   & {:keys [extends groups phases node-spec environment roles] :as options}]
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
                     #(keyword (str (name cluster-name)
                                    (if (blank? cluster-name) "" "-")
                                    (name %))))
                    (update-in
                     [:environment]
                     merge-environments environment)
                    (update-in [:roles] union roles)
                    (extend-specs extends)
                    (extend-specs [{:phases phases}])
                    (extend-specs [(select-keys group-spec [:phases])])))
                 (expand-group-spec-with-counts group-specs 1))))
   (dissoc :extends :node-spec)
   (assoc :cluster-cluster-name (keyword cluster-name))
   (vary-meta assoc :type ::cluster-spec)))

;;; ## Compute Service
;;;
;;; The compute service is used to communicate with the cloud provider
(defn compute-service
  "Returns a compute service object, used to perform actions on a cloud
  provider."
  [service-or-provider-name & options]
  (apply configure/compute-service service-or-provider-name options))

;;; ## Operations
;;;

(defn- process-phases
  "Process phases. Returns a phase list and a phase-map. Functions specified in
  `phases` are identified with a keyword and a map from keyword to function.
  The return vector contains a sequence of phase keywords and the map
  identifying the anonymous phases."
  [phases]
  (let [phases (if (or (keyword? phases) (fn? phases)) [phases] phases)]
    (reduce
     (fn [[phase-kws phase-map] phase]
       (if (or (keyword? phase)
               (and (or (vector? phase) (seq? phase)) (keyword? (first phase))))
         [(conj phase-kws phase) phase-map]
         (let [phase-kw (-> (gensym "phase")
                            name keyword)]
           [(conj phase-kws phase-kw)
            (assoc phase-map phase-kw phase)])))
     [[] {}] phases)))

(defn- groups-with-phases
  "Adds the phases from phase-map into each group in the sequence `groups`."
  [groups phase-map]
  (letfn [(add-phases [group]
            (update-in group [:phases] merge phase-map))]
    (map add-phases groups)))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn split-groups-and-targets
  "Split a node-set into groups and targets. Returns a map with
:groups and :targets keys"
  [node-set]
  (logging/tracef "split-groups-and-targets %s" (vec node-set))
  (->
   (group-by
    #(if (and (map? %)
              (every? map? (keys %))
              (every?
               (fn node-or-nodes? [x] (or (node? x) (sequential? x)))
               (vals %)))
       :targets
       :groups)
    node-set)
   (update-in
    [:targets]
    #(mapcat
      (fn [m]
        (reduce
         (fn [result [group nodes]]
           (if (sequential? nodes)
             (concat result (map (partial assoc group :node) nodes))
             (conj result (assoc group :node nodes))))
         []
         m))
      %))))

(defn- all-group-nodes
  "Returns a FSM to retrieve the service state for the specified groups"
  [compute groups all-node-set]
  (if compute
    (ops/group-nodes
     compute
     (concat
      groups
      (map
       (fn [g] (update-in g [:phases] select-keys [:settings]))
       all-node-set)))
    (result nil)))

(def ^{:doc "Arguments that are forwarded to be part of the environment"}
  environment-args [:compute :blobstore :user :provider-options])

(defn converge*
  "Returns a FSM to converge the existing compute resources with the counts
   specified in `group-spec->count`.  Options are as for `converge`."
  [group-spec->count & {:keys [compute blobstore user phase
                               all-nodes all-node-set environment plan-state]
                        :or {phase [:configure]}
                        :as options}]
  (check-converge-options options)
  (let [[phases phase-map] (process-phases phase)
        groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        groups (expand-group-spec-with-counts group-spec->count)
        {:keys [groups targets]} (-> groups
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        environment (merge-environments
                     (pallet.environment/environment compute)
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        lift-options (select-keys options ops/lift-options)]
    (doseq [group groups] (check-group-spec group))
    (dofsm converge
      [nodes-set (all-group-nodes compute groups all-node-set)
       nodes-set (result (concat nodes-set targets))
       _ (succeed
          (or compute (seq nodes-set))
          {:error :no-nodes-and-no-compute-service})
       {:keys [plan-state targets] :as converge-result}
       (ops/converge
        compute groups nodes-set plan-state environment phases options)

       {:keys [results plan-state] :as lift-result}
       (ops/lift-partitions
        targets plan-state environment (remove #{:settings :bootstrap} phases)
        lift-options)]

      (-> converge-result
          (update-in [:results] concat results)
          (assoc :plan-state plan-state)))))

(defn converge
  "Converge the existing compute resources with the counts specified in
`group-spec->count`. New nodes are started, or nodes are destroyed to obtain the
specified node counts.

`group-spec->count` can be a map from group-spec to node count, or can be a
sequence of group-specs containing a :count key.

This applies the `:bootstrap` phase to all new nodes and, by default,
the :configure phase to all running nodes whose group-name matches a key in the
node map.  Phases can also be specified with the `:phase` option, and will be
applied to all matching nodes.  The :configure phase is the default phase
applied.

## Options

`:compute`
: a compute service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Partitioning

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

## Post phase options

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### Algorithm options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys."
  [group-spec->count & {:keys [compute blobstore user phase
                               all-nodes all-node-set environment
                               async timeout-ms timeout-val]
                        :or {phase [:configure]}
                        :as options}]
  (load-plugins)
  (if async
    (operate (apply-map converge* group-spec->count options))
    (if timeout-ms
      (deref (operate (apply-map converge* group-spec->count options))
             timeout-ms timeout-val)
      (deref (operate (apply-map converge* group-spec->count options))))))

(defn lift*
  "Returns a FSM to lift the running nodes in the specified node-set by applying
   the specified phases.  Options as specified in `lift`."
  [node-set & {:keys [compute phase all-node-set environment]
               :or {phase [:configure]}
               :as options}]
  (check-lift-options options)
  (let [[phases phase-map] (process-phases phase)
        {:keys [groups targets]} (-> node-set
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        plan-state {}
        lift-options (select-keys options ops/lift-options)]
    (doseq [group groups] (check-group-spec group))
    (dofsm lift
      [nodes-set (all-group-nodes compute groups all-node-set)
       nodes-set (result (concat nodes-set targets))
       _ (succeed
          (or compute (seq nodes-set))
          {:error :no-nodes-and-no-compute-service})
       {:keys [plan-state]} (ops/lift
                             nodes-set plan-state environment [:settings] {})
       results (ops/lift-partitions
                nodes-set plan-state environment (remove #{:settings} phases)
                lift-options)]
      results)))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified
phases.  The compute service may be supplied as an option, otherwise the
bound compute-service is used.  The configure phase is applied by default
unless other phases are specified.

node-set can be a group spec, a sequence of group specs, or a map
of group specs to nodes. Examples:

    [group-spec1 group-spec2 {group-spec #{node1 node2}}]
    group-spec
    {group-spec #{node1 node2}}

## Options:

`:compute`
: a compute service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Partitioning

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

## Post phase options

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### Algorithm options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys."
  [node-set & {:keys [compute phase all-node-set environment
                      async timeout-ms timeout-val
                      partition-f post-phase-f post-phase-fsm
                      phase-execution-f execution-settings-f]
               :or {phase [:configure]}
               :as options}]
  (load-plugins)
  (if async
    (operate (apply-map lift* node-set options))
    (if timeout-ms
      (deref (operate (apply-map lift* node-set options))
             timeout-ms timeout-val)
      (deref (operate (apply-map lift* node-set options))))))

(defn lift-nodes
  "Lift `targets`, a sequence of node-maps, using the specified `phases`.  This
provides a way of lifting phases, which doesn't tie you to working with all
nodes in a group.  Consider using this only if the functionality in `lift` is
insufficient.

`phases`
: a sequence of phase keywords (identifying phases) or plan functions, that
  should be applied to the target nodes.  Note that there are no default phases.

## Options:

`:user`
: the admin-user to use for operations on the target nodes.

`:environment`
: an environment map, to be merged into the environment.

`:plan-state`
: an state map, which can be used to passing settings across multiple lift-nodes
  invocations.

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [targets phases
   & {:keys [user environment plan-state async timeout-ms timeout-val]
      :or {environment {} plan-state {}}
      :as options}]
  (let [[phases phase-map] (process-phases phases)
        targets (groups-with-phases targets phase-map)
        environment (merge-environments
                     environment
                     (select-keys options environment-args))]
    (letfn [(lift-nodes* []
              (operate
               (ops/lift-partitions
                targets phases environment plan-state
                (dissoc options
                        :environment :plan-state :async
                        :timeout-val :timeout-ms))))]
      (if async
        (lift-nodes*)
        (if timeout-ms
          (deref (lift-nodes*) timeout-ms timeout-val)
          (deref (lift-nodes*)))))))

(defn group-nodes
  "Return a sequence of node-maps for each node in the specified group-specs.

## Options:

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [compute groups & {:keys [async timeout-ms timeout-val]}]
  (letfn [(group-nodes* [] (operate (ops/group-nodes compute groups)))]
    (if async
      (group-nodes*)
      (if timeout-ms
        (deref (group-nodes*) timeout-ms timeout-val)
        (deref (group-nodes*))))))

;;; ### plan functions
(defmacro plan-fn
  "Create a plan function from a sequence of plan function invocations.

   eg. (plan-fn
         (file \"/some-file\")
         (file \"/other-file\"))

   This generates a new plan function, and adds code to verify the state
   around each plan function call."
  [& body]
  (let [n? (string? (first body))
        n (when n? (first body))
        body (if n? (rest body) body)]
    (if n
      `(fn [] (phase-context ~(gensym n) {} ~@body))
      `(fn [] (session-context ~(gensym "a-plan-fn") {} ~@body)))))

;;; ### Admin user
(defn make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and pallet.api/with-admin-user, or passed
   to `lift` or `converge` as the named :user argument.

   Options:
    - :public-key-path (defaults to ~/.ssh/id_rsa.pub)
    - :private-key-path (defaults to ~/.ssh/id_rsa)
    - :passphrase
    - :password
    - :sudo-password (defaults to :password)
    - :no-sudo"
  [username & {:keys [public-key-path private-key-path passphrase
                      password sudo-password no-sudo sudo-user] :as options}]
  (check-user
   (user/make-user
    username
    (merge
     {:private-key-path (user/default-private-key-path)
      :public-key-path (user/default-public-key-path)
      :sudo-password (:password options)}
     options))))

(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is specified
   either as pallet.utils.User record (see the pallet.utils/make-user
   convenience fn) or as an argument list that will be passed to make-user.

   This is mainly for use at the repl, since the admin user can be specified
   functionally using the :user key in a lift or converge call, or in the
   environment."
  [user & exprs]
  `(binding [user/*admin-user* ~user]
    ~@exprs))

(defn print-nodes
  "Print the targets of an operation"
  [nodes]
  (let [ks [:primary-ip :private-ip :hostname :group-name :roles]]
    (print-table ks
                 (for [node nodes
                       :let [node (node-map node)]]
                   (select-keys node ks)))))

(defn print-targets
  "Print the targets of an operation"
  [op]
  (let [ks [:primary-ip :private-ip :hostname :group-name :roles]]
    (print-table ks
                 (for [{:keys [node roles]} (:targets op)]
                   (assoc (select-keys (node-map node) ks)
                     :roles roles)))))
