(ns pallet.group
  "Pallet Group functions for adjusting nodes

Provides the node-spec, service-spec and group-spec abstractions.

Provides the lift and converge operations.

Uses a TargetMap to describe a node with its group-spec info."
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :as async :refer [<! <!! alts!! chan close! timeout]]
   [clojure.set :refer [union]]
   [clojure.string :as string :refer [blank?]]
   [taoensso.timbre :as logging :refer [debugf tracef]]
   [pallet.blobstore :refer [blobstore?]]
   [pallet.compute :as compute
    :refer [check-node-spec compute-service? node-spec
            node-spec-schema service-properties]]
   [pallet.crate.node-info :as node-info]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.node :as node :refer [node?]]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.environment :refer [merge-environments]]
   [pallet.exception :refer [combine-exceptions]]
   [pallet.phase :as phase
    :refer [phases-with-meta process-phases phase-schema]]
   [pallet.plan :refer [errors]]
   [pallet.session :as session
    :refer [base-session? extension plan-state
            recorder target target-session? update-extension]]
   [pallet.spec :as spec
    :refer [default-phase-meta extend-specs merge-spec-algorithm merge-specs
            targets target-with-specs]]
   [pallet.target :as target]
   [pallet.target-info :refer [admin-user]]
   [pallet.target-ops
    :refer [create-targets destroy-targets lift-op lift-phase]]
   [pallet.user :as user]
   [pallet.utils :refer [maybe-update-in total-order-merge]]
   [pallet.utils.async
    :refer [channel? concat-chans exec-operation from-chan go-logged go-try
            sync]]
   [schema.core :as schema :refer [check required-key optional-key validate]])
  (:import clojure.lang.IFn))

;;; # Domain Model

;;; ## Schemas

(def environment-strict-schema
  {(optional-key :user) user/user-schema
   (optional-key :executor) IFn
   (optional-key :compute) (schema/pred compute-service?)})

(def group-spec-schema
  (merge spec/server-spec-schema
         {:group-name schema/Keyword
          (optional-key :node-filter) IFn
          (optional-key :count) Number
          (optional-key :removal-selection-fn) IFn
          (optional-key :node-spec) node-spec-schema}))

(def lift-options-schema
  (merge
   environment-strict-schema
   {(optional-key :compute) (schema/pred compute-service?)
    (optional-key :blobstore) (schema/pred blobstore?)
    (optional-key :phase) (schema/either phase-schema [phase-schema])
    (optional-key :environment) (assoc environment-strict-schema
                                  schema/Keyword schema/Any)
    (optional-key :user) user/user-schema
    (optional-key :async) schema/Bool
    (optional-key :timeout-ms) Number
    (optional-key :timeout-val) schema/Any
    (optional-key :debug) {(optional-key :script-comments) schema/Bool
                           (optional-key :script-trace) schema/Bool}
    (optional-key :os-detect) schema/Bool
    (optional-key :all-node-set) #{schema/Any}}))

(def converge-options-schema
  (->
   lift-options-schema
   (dissoc (optional-key :compute))
   (assoc :compute (schema/pred compute-service?))))

(defn check-group-spec
  [m]
  (validate group-spec-schema m))

(defn check-lift-options
  [m]
  (validate lift-options-schema m))

(defn check-converge-options
  [m]
  (validate converge-options-schema m))

;;; ## Group-spec

(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to its node-spec

   - :extends        specify a server-spec, a group-spec, or sequence thereof
                     and is used to inherit phases, etc.

   - :phases         used to define phases. Standard phases are:
   - :phases-meta    metadata to add to the phases
   - :default-phases a sequence specifying the default phases
   - :bootstrap      run on first boot of a new node
   - :configure      defines the configuration of the node.

   - :count          specify the target number of nodes for this node-spec
   - :packager       override the choice of packager to use
   - :node-spec      default node-spec for this group-spec
   - :node-filter    a predicate that tests if a node is a member of this
                     group.

   - :removal-selection-fn a function that will be called to select
                           nodes for removal. Arguments are the number of
                           nodes to select and a sequence of current nodes."
  ;; Note that the node-filter is not set here for the default group-name based
  ;; membership, so that it does not need to be updated by functions that modify
  ;; a group's group-name.
  [name {:keys [extends count image phases phases-meta default-phases packager
                node-spec roles node-filter]
         :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (let [group-name (keyword (clojure.core/name name))]
    (check-group-spec
     (->
      (merge options)
      (cond->
       roles (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
      (extend-specs extends)
      (maybe-update-in
       [:phases] phases-with-meta phases-meta default-phase-meta)
      (update-in [:default-phases] #(or default-phases % [:configure]))
      (dissoc :extends :phases-meta)
      (assoc :group-name group-name)
      (vary-meta assoc :type ::group-spec)))))

;;; ## Cluster Spec
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
   & {:keys [extends groups phases node-spec roles] :as options}]
  (let [cluster-name (name cluster-name)
        group-prefix (if (blank? cluster-name) "" (str cluster-name "-"))]
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
                       #(keyword (str group-prefix (name %))))
                      (update-in [:roles] union roles)
                      (extend-specs extends)
                      (extend-specs [{:phases phases}])
                      (extend-specs [(select-keys group-spec [:phases])])))
                   (expand-group-spec-with-counts group-specs 1))))
     (dissoc :extends :node-spec)
     (assoc :cluster-name (keyword cluster-name))
     (vary-meta assoc :type ::cluster-spec))))


;;; # Plan-state scopes
(defn target-scopes
  [target]
  (merge {:group (:group-name target)
          :universe true}
         (if-let [node (:node target)]
           {:host (node/id node)
            :service (node/compute-service node)
            :provider (:provider
                       (service-properties
                        (node/compute-service node)))})))

;; (ann group-name [Session -> GroupName])
;; (defn group-name
;;   "Group name of the target-node."
;;   [session]
;;   {:pre [(target-session? session)]}
;;   (-> session :target :group-name))

;;; # Target Extension Functions

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  [session group-name]
  (->>
   (targets session)
   (filter
    (fn [t]
      (or (= (:group-name t) group-name)
          (when-let [group-names (:group-names t)]
            (get group-names group-name)))))))

(defn groups-with-role
  "All target groups with the specified role."
  [session role]
  (->>
   (targets session)
   (filter (fn [t] ((:roles t #{}) role)))
   (map (fn [t] (dissoc t :node)))
   ((fn [x] (distinct x)))))

;; (defn groups-with-role
;;   "All target groups with the specified role."
;;   [session role]
;;   (->>
;;    @(:system-targets session)
;;    (filter #((:roles % #{}) role))
;;    (map #(dissoc % :node))
;;    distinct))

(defn nodes-with-role
  "All target nodes with the specified role."
  [session role]
  (->> (targets session)
       (filter
        (fn [node]
          (when-let [roles (:roles node)]
            (roles role))))))

(defn role->nodes-map
  "Returns a map from role to nodes."
  [session]
  (reduce
   (fn [m node]
     (reduce (fn [m role]
               (update-in m [role] conj node))
             m
             (:roles node)))
   {}
   (targets session)))

;; (defn target-group-name
;;   "Return the group name for node, as recorded in the session-targets."
;;   [session node]
;;   (:group-name (session-target session node)))


;;; # Group Name Functions

;;; We tag nodes with the group-name if possible, else fall back on
;;; relying on the encoding of the group name into the node name.

(def group-name-tag
  "The name of the tag used to record the group name on the node."
  "/pallet/group-name")

(defn target-has-group-name?
  "Return a predicate to check if a target node has the specified group name.
  If the node is taggable, we check the group-name-tag, otherwise we
  fall back onto checking the whether the node's base-name matches the
  group name."
  [target group-name]
  {:pre [(target/node target)]}
  (if (target/taggable? target)
    (= group-name (target/tag target group-name-tag))
    (target/has-base-name? target group-name)))

(defn target-in-group?
  "Check if a node satisfies a group's node-filter."
  {:internal true}
  [target group]
  {:pre [(target/node target)
         (check-group-spec group)]}
  (debugf "node-in-group? target %s group %s" target group)
  (debugf "node-in-group? target in group %s"
          (target-has-group-name? target (name (:group-name group))))
  ((:node-filter group #(target-has-group-name? % (name (:group-name group))))
   target))

;;; # Map Nodes to Groups Based on Group's Node-Filter
(defn service-state
  "For a sequence of targets, filter those nodes in the specified
  `groups`. Returns a sequence that contains a target-map for each
  matching target."
  [targets groups]
  {:pre [(every? #(check-group-spec %) groups)
         (every? target/node targets)]}
  (tracef "service-state %s" (vec targets))
  (let [group-member? (fn [group]
                        (let [group-name (name (:group-name group))]
                          (:node-filter
                           group
                           (fn [target]
                             (target-has-group-name? target group-name)))))
        predicate-spec-pairs (map (juxt group-member? identity) groups)
        _ (debugf "service-state p-s-p %s" (pr-str predicate-spec-pairs))
        targets (map #(target-with-specs predicate-spec-pairs %) targets)]
    (debugf "service-state targets %s" (vec targets))
    (filter :group-name targets)))

;;; # Operations

(def ^{:doc "node-specific environment keys"}
  node-keys [:image :phases])

(defn group-with-environment
  "Add the environment to a group."
  [environment group]
  (merge-environments
   (maybe-update-in (select-keys environment node-keys)
                    [:phases] phases-with-meta {} default-phase-meta)
   group
   (maybe-update-in (get-in environment [:groups group])
                    [:phases] phases-with-meta {} default-phase-meta)))

;;; ## Calculation of node count adjustments
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
  (debugf "group-delta targets %s group %s" (vec targets) group)
  (let [existing-count (count targets)
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw
       (ex-info
        (format "Node :count not specified for group: %s" group)
        {:reason :target-count-not-specified
         :group group}) (:group-name group)))
    {:actual existing-count
     :target target-count
     :targets targets
     :delta (- target-count existing-count)
     :group group}))

(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  (debugf "group-deltas targets %s groups %s" (vec targets) (vec groups))
  (map
   (fn [group]
     (group-delta (filter
                   (fn [t] (target-in-group? t group))
                   targets)
                  group))
   groups))

;;; ### Nodes and Groups to Remove
(defn group-removal-spec
  "Return a map describing the group and targets to be removed."
  [{:keys [group target targets delta]}]
  (let [f (:removal-selection-fn group take)]
    {:group group
     :targets (f (- delta)
                 (filter
                  (fn [target] (target-in-group? target group))
                  targets))
     :remove-group (zero? target)}))

(defn group-removal-specs
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [group-deltas]
  (->>
   group-deltas
   (filter (comp neg? :delta))
   (map group-removal-spec)))

;;; ### Nodes and Groups to Add

(defn group-add-spec
  [{:keys [group delta actual] :as group-delta}]
  {:count delta
   :group group
   :create-group (and (zero? actual) (pos? delta))})

(defn group-add-specs
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [group-deltas]
  (->>
   group-deltas
   (filter (comp pos? :delta))
   (map group-add-spec)))

;;; ## Node creation and removal
(defn remove-nodes
  "Removes `targets` from `group`. If `:remove-group` is true, then
  all nodes for the group are being removed, and the group should be
  removed.  Puts a result onto the output channel, ch, as a rex-tuple
  where the value is a map with :destroy-servers, :old-node-ids, and
  destroy-groups keys."
  [session compute-service group remove-group targets ch]
  (go-try ch
    (let [c (chan 1)]
      (destroy-targets session compute-service targets c)
      (let [[{:keys [destroy-server old-node-ids] :as m} e
             :as phase-res] (<! c)]
        (debugf "remove-nodes %s %s %s %s"
                remove-group
                (count destroy-server)
                (count old-node-ids)
                (count targets))
        (if (and remove-group (= (count destroy-server)
                                 (count old-node-ids)
                                 (count targets)))
          (do
            (lift-phase
             session :destroy-group [(assoc group :target-type :group)] nil c)
            (let [[gr ge :as group-res] (<! c)]
              (>! ch [(assoc m :destroy-group gr)
                      (combine-exceptions (filter identity [e ge]))])))
          (>! ch phase-res))))))

(defn remove-group-nodes
  "Removes targets from groups accordingt to the removal-specs
  sequence.  The result is written to the channel, ch.
  Each removal-spec is a map with :group, :remove-group and :targets keys."
  [session compute-service removal-specs ch]
  (debugf "remove-group-nodes %s" (seq removal-specs))
  (go-try ch
    (let [c (chan)]
      (doseq [{:keys [group remove-group targets]} removal-specs]
        (remove-nodes session compute-service group remove-group targets c))
      (loop [n (count removal-specs)
             res []
             exceptions []]
        (debugf "remove-group-nodes res %s" res)
        (if-let [[r e] (and (pos? n) (<! c))]
          (recur (dec n) (conj res r) (if e (conj exceptions e) exceptions))
          (>! ch [res (combine-exceptions exceptions)]))))))

(defn add-nodes
  "Create `count` nodes for a `group`."
  [session compute-service group count create-group ch]
  {:pre [(base-session? session)]}
  (debugf "add-nodes %s %s" group count)
  (go-try ch
    (let [c (chan)
          [result e] (if create-group
                       (do
                         (lift-phase
                          session :create-group
                          [(assoc group :target-type :group)] nil c)
                         (<! c)))]
      (if e
        (>! ch [{:create-group result} e])
        (do
          (create-targets
           session
           compute-service
           (:node-spec group)
           group
           (session/user session)
           count
           {:node-name (name (:group-name group))}
           c)
          (let [[{:keys [bootstrap targets] :as m} e2] (<! c)]
            (>! ch [(merge m {:create-group result})
                    e2])))))))

(defn add-group-nodes
  "Create nodes for groups."
  [session compute-service group-add-specs ch]
  {:pre [(base-session? session)]}
  (debugf "add-group-nodes %s %s" compute-service (vec group-add-specs))
  (go-try ch
    ;; We use a buffered channel so as not blocked when we read the
    ;; channel returned by add-group-nodes
    (let [c (chan (count group-add-specs))]
      (doseq [{:keys [group count create-group]} group-add-specs]
        (add-nodes session compute-service group count create-group c))
      (loop [n (count group-add-specs)
             res []
             exceptions []]
        (debugf "add-group-nodes res %s" res)
        (if-let [[r e] (and (pos? n) (<! c))]
          (recur (dec n) (conj res r) (if e (conj exceptions e) exceptions))
          (>! ch [res (combine-exceptions exceptions)]))))))

;;; # Execution helpers

;;; Node count adjuster
(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them.
Return a map.  The :new-targets key will contain a sequence of new
targets; the :old-node-ids a sequence of removed node-ids,
and :results a sequence of phase results from
the :destroy-server, :destroy-group, and :create-group phases."
  [session compute-service groups targets ch]
  {:pre [(base-session? session)
         compute-service
         (every? :count groups)
         (every? (some-fn :node :group) targets)]}
  (debugf "node-count-adjuster targets %s" (vec targets))
  (go-try ch
    (let [group-deltas (group-deltas targets groups)
          removal-specs (group-removal-specs group-deltas)
          add-specs (group-add-specs group-deltas)

          targets-map (reduce #(assoc %1 (node/id (:node %2)) %2)
                              {} targets)
          c-remove (chan)
          c-add (chan)]
      (debugf "node-count-adjuster group-deltas %s" (vec group-deltas))
      (debugf "node-count-adjuster removal-specs %s" (vec removal-specs))

      (remove-group-nodes session compute-service removal-specs c-remove)
      (add-group-nodes session compute-service add-specs c-add)

      (let [[res-remove e-remove] (<! c-remove)
            [res-add e-add] (<! c-add)
            old-node-ids (mapcat :old-node-ids res-remove)
            targets-map (apply dissoc targets-map old-node-ids)
            targets (concat (vals targets-map) (mapcat :targets res-add))
            result {:results (concat (mapcat :destroy-server res-remove)
                                     (mapcat :destroy-group res-remove)
                                     (mapcat :results res-add))
                    :targets targets
                    :old-node-ids old-node-ids}]
        (debugf "node-count-adjuster res-remove %s" (vec res-remove))
        (debugf "node-count-adjuster result %s" result)
        (>! ch [result (combine-exceptions [e-remove e-add])])))))

;;; ## Operations
;;;

;; (defmethod target-id-map :group [target]
;;   (select-keys target [:group-name]))

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

(defn all-group-nodes
  "Returns the service state for the specified groups"
  [compute groups all-node-set]
  {:pre [(or (empty? groups) (compute-service? compute))
         (every? #(check-group-spec %) groups)
         (every? #(check-group-spec %) all-node-set)]}
  (let [consider (map
                  (fn [g] (update-in g [:phases] select-keys [:settings]))
                  all-node-set)]
    (if (seq groups)
      (service-state (sync (compute/targets compute)) (concat groups consider))
      consider)))

(def ^{:doc "Arguments that are forwarded to be part of the environment"}
  environment-args [:compute :blobstore :provider-options])

(defn group-node-maps
  "Return the nodes for the specified groups."
  [compute groups & {:keys [async timeout-ms timeout-val] :as options}]
  (all-group-nodes compute groups nil) options)

(def ^{:doc "A sequence of keywords, listing the lift-options"}
  lift-options
  [:targets :phase-execution-f :execution-settings-f
   :post-phase-f :post-phase-fsm :partition-f])

(defn converge*
  "Converge the existing compute resources with the counts
   specified in `group-spec->count`.  Options are as for `converge`.
   The result is written to the channel, ch."
  [group-spec->count ch {:keys [compute blobstore user phase
                                all-nodes all-node-set environment
                                plan-state debug os-detect]
                         :or {os-detect true}
                         :as options}]
  (check-converge-options options)
  (logging/tracef "environment %s" environment)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (merge phase-map (:phases (node-info/server-spec {})))
                    phase-map)
        _ (debugf "phase-map %s" phase-map)
        groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        groups (expand-group-spec-with-counts group-spec->count)
        {:keys [groups targets]} (-> groups
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        lift-options (select-keys options lift-options)
        initial-plan-state (or plan-state {})
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        phases (or (seq phases)
                   (apply total-order-merge
                          (map
                           #(get % :default-phases [:configure])
                           (concat groups targets))))]
    (debugf "converge* targets %s" (vec targets))
    (doseq [group groups] (check-group-spec group))
    (go-try ch
      (>! ch
          (let [session (session/create
                         {:executor (ssh/ssh-executor)
                          :plan-state (in-memory-plan-state initial-plan-state)
                          :user user/*admin-user*})
                nodes-set (all-group-nodes compute groups all-node-set)
                nodes-set (concat nodes-set targets)
                _  (debugf "nodes-set before converge %s" (vec nodes-set))
                _ (when-not (or compute (seq nodes-set))
                    (throw (ex-info
                            "No source of nodes"
                            {:error :no-nodes-and-no-compute-service})))
                c (chan)
                _ (node-count-adjuster session compute groups nodes-set c)
                [converge-result e] (<! c)]
            (debugf "new targets %s" (vec (:targets converge-result)))
            (debugf "old ids %s" (vec (:old-node-ids converge-result)))
            (if e
              [converge-result e]
              (let [nodes-set (:targets converge-result)
                    _ (debugf "nodes-set after converge %s" (vec nodes-set))
                    phases (concat (when os-detect [:pallet/os-bs :pallet/os])
                                   [:settings :bootstrap] phases)]
                (debugf "running phases %s" (vec phases))
                (lift-op session phases nodes-set lift-options c)
                (let [[result e] (<! c)]
                  [(-> converge-result
                       (update-in [:results] concat result))
                   e]))))))))

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

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

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

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [group-spec->count & {:keys [compute blobstore user phase
                               all-nodes all-node-set environment
                               async timeout-ms timeout-val
                               debug plan-state]
                        :as options}]
  ;; TODO  (load-plugins)
  (let [ch (chan)]
    (converge* group-spec->count ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

(defn lift*
  "Asynchronously execute a lift of phases on the node-set.  Options
  as specified in `lift`."
  [node-set ch {:keys [compute phase all-node-set environment debug plan-state
                       os-detect]
                :or {os-detect true}
                :as options}]
  (logging/trace "Lift*")
  (check-lift-options options)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (merge phase-map (:phases (node-info/server-spec {})))
                    phase-map)
        {:keys [groups targets]} (-> node-set
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        _ (logging/tracef "options %s" options)
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        initial-plan-state (or plan-state {})
        ;; TODO
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        lift-options (select-keys options lift-options)
        phases (or (seq phases)
                   (apply total-order-merge
                          (map :default-phases (concat groups targets))))]
    (doseq [group groups] (check-group-spec group))
    (logging/trace "Lift ready to start")
    (go-try ch
      (>! ch
          (let [session (session/create
                         {:executor (ssh/ssh-executor)
                          :plan-state (in-memory-plan-state initial-plan-state)
                          :user user/*admin-user*})
                nodes-set (all-group-nodes compute groups all-node-set)
                nodes-set (concat nodes-set targets)
                ;; TODO put nodes-set target maps into :extensions
                _ (when-not (or compute (seq nodes-set))
                    (throw (ex-info "No source of nodes"
                                    {:error :no-nodes-and-no-compute-service})))
                _ (logging/trace "Retrieved nodes")
                c (chan)
                _ (lift-op
                   session
                   (concat
                    (when os-detect [:pallet/os :pallet/os-bs])
                    [:settings])
                   nodes-set
                   {}
                   c)
                [settings-results e] (<! c)
                errs (errors settings-results)
                result {:results settings-results
                        :session session
                        :targets nodes-set}]
            (cond
             e [result e]
             errs [result (ex-info "settings phase failed" {:errors errs})]
             :else (do
                     (lift-op session phases nodes-set lift-options c)
                     (let [[lift-results e] (<! c)
                           errs (errors lift-results)
                           result (update-in result [:results]
                                             concat lift-results)]
                       [result (or e
                                   (if errs
                                     (ex-info "phase failed"
                                              {:errors errs})))]))))))))

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

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

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

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [node-set & {:keys [compute phase user all-node-set environment
                      async timeout-ms timeout-val
                      partition-f post-phase-f post-phase-fsm
                      phase-execution-f execution-settings-f
                      debug plan-state]
               :as options}]
  (logging/trace "Lift")
  ;; TODO (load-plugins)
  (let [ch (chan)]
    (lift* node-set ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

;;; # Exception and Error Reporting
(defn phase-errors
  "Return a sequence of phase errors for an operation.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [result]
  (->> (:results result) errors))

(defn phase-error-exceptions
  "Return a sequence of exceptions from phase errors for an operation. "
  [result]
  (->>  (phase-errors result)
        (map (comp :cause :error))
        (filter identity)))

(defn throw-phase-errors
  [result]
  (when-let [e (phase-errors result)]
    (throw
     (ex-info
      (str "Phase errors: " (string/join " " (map (comp :message :error) e)))
      {:errors e}
      (or (-> (first e) :message :exception)
          (-> (first (remove nil? (map (comp :cause :error) e)))))))))
