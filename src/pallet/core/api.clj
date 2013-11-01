(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.algo.monads :refer [domonad m-map state-m with-monad]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [pallet.core.type-annotations]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :refer [destroy-node destroy-nodes-in-group nodes run-nodes]]
   [pallet.core.api-impl :refer :all]
   [pallet.core.protocols :refer [ComputeService]]
   [pallet.core.session :refer [session session! with-session]]
   [pallet.core.types
    :refer [assert-not-nil assert-type-predicate keyword-map?
            Action ActionErrorMap ActionResult EnvironmentMap ExecSettings
            ExecSettingsFn GroupName GroupSpec IncompleteServiceState Keyword
            Phase PhaseResult PhaseTarget PlanState Result ServiceState Session
            TargetMap TargetPhaseResult User]]
   [pallet.core.user :refer [obfuscated-passwords]]
   [pallet.execute :refer [parse-shell-result]]
   [pallet.node
    :refer [id image-user group-name primary-ip tag tag! taggable? terminated?]]
   [pallet.session.verify :refer [add-session-verification-key check-session]]
   [pallet.stevedore :refer [with-source-line-comments]])
  (:import
   clojure.lang.IMapEntry
   pallet.core.protocols.Node))

(ann version (Fn [-> (Nilable String)]))
(let [v (atom (ann-form nil (Nilable String)))]
  (defn version
    "Returns the pallet version."
    []
    (or
     @v
     (reset! v (System/getProperty "pallet.version"))
     (reset! v (if-let [version (slurp (io/resource "pallet-version"))]
                       (string/trim version))))))

(ann service-state [ComputeService (Nilable (NonEmptySeqable GroupSpec))
                    -> IncompleteServiceState])
(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a sequence that contains a node-map for each
  matching node."
  [compute-service groups]
  (let [nodes (remove terminated? (nodes compute-service))]
    (tracef "service-state %s" (vec nodes))
    (seq (remove nil? (map (node->node-map groups) nodes)))))


(defn service-groups
  "Query the available nodes in a `compute-service`, returning a group-spec
  for each group found."
  [compute-service]
  (->> (nodes compute-service)
       (remove terminated?)
       (map group-name)
       (map #(hash-map :group-name %))
       (map #(vary-meta % assoc :type ::group-spec))))

;;; # Execute action on a target node
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
(tc-ignore
 (defn execute-action
   "Execute an action map within the context of the current session."
   [session action]
   (debugf "execute-action %s" (pr-str action))
   (let [executor (get session ::executor)
         execute-status-fn (get session ::execute-status-fn)
         _ (debugf "execute-action executor %s" (pr-str executor))
         _ (debugf "execute-action execute-status-fn %s"
                   (pr-str execute-status-fn))
         _ (assert executor "No executor in session")
         _ (assert execute-status-fn "No execute-status-fn in session")
         ;; TODO use destructuring when core.typed can grok it
         rrv (executor session action)
         [rv _] rrv
         out (:out rv)
         _ (debugf "execute-action rv %s" (pr-str rv))
         _ (assert (map? rv)
                   (str "Action return value must be a map: " (pr-str rrv)))
         [rv session] (parse-shell-result session rv)]
     ;; TODO add retries, timeouts, etc
     (session!
      (update-in session [:phase-results]
                 (fnil conj [])
                 rv))
     (execute-status-fn rv)
     rv)))


;;; # Execute a phase on a target node
(ann stop-execution-on-error [ActionResult -> nil])
(defn stop-execution-on-error
  ":execute-status-fn algorithm to stop execution on an error"
  [result]
  (when (:error result)
    (debugf "Stopping execution %s" (:error result))
    (let [msg (-> result :error :message)]
      (throw (ex-info
              (str "Phase stopped on error" (if msg (str " - " msg)))
              {:error (:error result)
               :message msg}
              (get (get result :error) :exception))))))

;; TODO remoe :no-check when core.typed can use * in Vector*
(ann ^:no-check phase-args [Phase -> (Nilable (NonEmptySeqable Any))])
(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

;; TODO remove :no-check when core.typed can handle first with a Vector*
(ann ^:no-check phase-kw [Phase -> Keyword])
(defn- phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

;; TODO remove no-check when get gets smarter
(ann ^:no-check target-phase [PhaseTarget Phase -> [Any * -> Any]])
(defn target-phase [target phase]
  (tracef "target-phase %s %s" target phase)
  ;; TODO switch back to keyword invocation when core.typed can handle it
  (get (get target :phases) (phase-kw phase)))

;; TODO remove :no-check when core.typed understands build a Map type properly
(ann ^:no-check action-plan
     [ServiceState EnvironmentMap Phase ExecSettingsFn
      (U (Value :server) (Value :group))
      PhaseTarget -> [PlanState -> TargetPhaseResult]])
(defn action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups. `target-map` is a map for the session
  describing the target."
  [service-state environment phase execution-settings-f target-kw target]
  {:pre [(map? target)
         (or (nil? environment) (map? environment))]}
  (let [plan-fn (target-phase target phase)
        args (phase-args phase)
        phase-kw (phase-kw phase)]
    (assert (and (not (map? plan-fn)) (fn? plan-fn))
            "plan-fn should be a function")
    (fn> action-plan [plan-state :- PlanState]
         (let [{:keys [user executor execute-status-fn]}
               (execution-settings-f environment target)
               [rv session]
               (with-session
                 (add-session-verification-key
                  (merge {:user (get environment :user)}
                         {:service-state service-state
                          :plan-state plan-state
                          :environment environment
                          target-kw target
                          ::executor executor
                          ::execute-status-fn execute-status-fn}))
                 ;; TODO remove tc-ignore if core.type supports apply
                 [(tc-ignore (apply plan-fn args)) (session)])]
           {:result (:phase-results session)
            :plan-state (:plan-state session)
            :target target
            :phase phase-kw
            :phase-args args}))))

;; TODO remove no-check when commons is type checked
(ann ^:no-check target-action-plan
     [ServiceState PlanState EnvironmentMap Phase ExecSettingsFn TargetMap
      -> [PlanState -> TargetPhaseResult]])
(defmulti target-action-plan
  "Build action plans for the specified `phase` on all nodes or groups in the
  given `target`, within the context of the `service-state`. The `plan-state`
  contains all the settings, etc, for all groups."
  (fn target-action-plan
    [service-state plan-state environment phase execution-settings-f target]
    (tracef "target-action-plan %s" (:target-type target :node))
    (:target-type target :node)))

(defmethod target-action-plan :node
  [service-state plan-state environment phase execution-settings-f target]
  {:pre [target (:node target) phase]}
  (fn [plan-state]
    (logutils/with-context [:target (get (get target :node) primary-ip)]
      (with-script-for-node target plan-state
        ((action-plan
          service-state
          environment
          phase
          execution-settings-f
          :server target)
         plan-state)))))

(defmethod target-action-plan :group
  [service-state plan-state environment phase execution-settings-f group]
  {:pre [group]}
  (fn [plan-state]
    (logutils/with-context [:target (-> group :group-name)]
      ((action-plan
        service-state
        environment
        phase
        execution-settings-f
        :group group)
       plan-state))))

(ann execute-phase-on-target
     [ServiceState PlanState EnvironmentMap Phase ExecSettingsFn TargetMap
      -> TargetPhaseResult])
(defn execute-phase-on-target
  "Execute a phase on a single target."
  [service-state plan-state environment phase execution-settings-f target-map]
  (let [f (target-action-plan
           service-state plan-state environment phase execution-settings-f
           target-map)]
    (f plan-state)))



;; (defn execute-phase
;;   [service-state plan-state environment phase execution-settings-f targets]
;;   (let [targets-with-phase (filter #(target-phase % phase) targets)
;;         result-chans (doall
;;                       (for [target targets-with-phase]
;;                         (execute-phase-on-target
;;                          service-state plan-state environment phase
;;                          execution-settings-f target)))
;;         timeout (timeout (* 5 60 1000))] ; TODO make this configurable
;;     (tracef
;;      "action-plans: phase %s targets %s targets-with-phase %s"
;;      phase (vec targets) (vec targets-with-phase))
;;     (map #(alts!! % timeout) result-chans)))


;;; ## Action Plan Execution
;; TODO remove no-check when find out why this hangs core.typed
(ann ^:no-check environment-execution-settings [-> ExecSettingsFn])
(defn environment-execution-settings
  "Returns execution settings based purely on the environment"
  []
  (fn> [environment :- EnvironmentMap
        _ :- PhaseTarget]
    (debugf "environment-execution-settings %s" environment)
    (debugf "Env user %s" (obfuscated-passwords (into {} (:user environment))))
    {:user (:user environment)
     :executor (get-in environment [:algorithms :executor])
     :execute-status-fn (get-in environment [:algorithms :execute-status-fn])}))

;; TODO remove :no-check when core.typed's map types are more powerful
(ann ^:no-check environment-image-execution-settings [-> ExecSettingsFn])
(defn environment-image-execution-settings
  "Returns execution settings based on the environment and the image user."
  []
  (fn> [environment :- EnvironmentMap
        node :- PhaseTarget]
       (let [user (into {} (filter (inst val Any)
                                   (image-user (assert-not-nil
                                                (get node :node)))))
             user (if (or (get user :private-key-path) (get user :private-key))
                    (assoc user :temp-key true)
                    user)]
         (tc-ignore
          (debugf "Image-user is %s" (obfuscated-passwords user)))
         {:user user
          ;; TODO revert to get-in when core.typed powerful enough
          :executor (get (get environment :algorithms) :executor)
          :execute-status-fn (get (get environment :algorithms)
                                  :execute-status-fn)})))

;;; ## Calculation of node count adjustments
(def-alias NodeDeltaMap (HMap :mandatory {:actual AnyInteger
                                          :target AnyInteger
                                          :delta AnyInteger}))
(def-alias GroupDelta '[GroupSpec NodeDeltaMap])
(def-alias GroupDeltaSeq (Nilable (NonEmptySeqable GroupDelta)))

(ann group-delta [ServiceState GroupSpec -> NodeDeltaMap])
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
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
     :delta (- target-count existing-count)}))

(ann group-deltas [ServiceState (Seq GroupSpec) -> GroupDeltaSeq])
(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  (seq
   ((inst map GroupDelta GroupSpec)
    (juxt
     (inst identity GroupSpec)
     (fn> [group :- GroupSpec]
       ;; TODO - remove seq when CTYP-84 fixed
       (group-delta (seq (filter
                          (fn> [t :- TargetMap]
                            (node-in-group? (get t :node) group))
                          targets))
                    group)))
    groups)))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-create [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-create
  "Return a sequence of groups that currently have no nodes, but will have nodes
  added."
  [group-deltas]
  (letfn> [new-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (new-group? [delta]
            (and (zero? (get delta :actual)) (pos? (get delta :target))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (new-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta) (inst first GroupSpec))
     ((inst map GroupSpec GroupSpec)
      (fn> [group-spec :- GroupSpec]
           (assoc group-spec :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-remove [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-remove
  "Return a sequence of groups that have nodes, but will have all nodes
  removed."
  [group-deltas]
  (letfn> [remove-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (remove-group? [delta]
            (and (zero? (get delta :target)) (pos? (get delta :actual))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (remove-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta)
      (fn> [group-delta :- GroupDelta]
           (assoc (first group-delta) :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check nodes-to-remove [ServiceState GroupDeltaSeq
                                 -> (HMap :mandatory {:targets (Seq TargetMap)}
                                          :optional {:all boolean})])
(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [targets group-deltas]
  (letfn> [pick-servers :- [GroupDelta
                            -> (Vector*
                                GroupSpec
                                (HMap :mandatory
                                      {:nodes (NonEmptySeqable TargetMap)
                                       :all boolean}))]
           ;; TODO revert to destructuring when supported by core.typed
           (pick-servers [group-delta]
             (let
                 [group ((inst first GroupSpec) group-delta)
                  dm ((inst second NodeDeltaMap) group-delta)
                  target (get dm :target)
                  delta (get dm :delta)]
               (vector
                group
                {:nodes (take (- delta)
                              (filter
                               (fn> [target :- TargetMap]
                                    (node-in-group? (get target :node) group))
                               targets))
                 :all (zero? target)})))]
    (into {}
          (->>
           group-deltas
           (filter (fn> [group-delta :- GroupDelta]
                        (when (neg? (:delta (second group-delta)))
                          group-delta)))
           (map pick-servers)))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check nodes-to-add
     [GroupDeltaSeq -> (Seqable '[GroupSpec AnyInteger])])
(defn nodes-to-add
  "Finds the specified number of nodes to be added to the given groups.
  Returns a map from group to a count of servers to add"
  [group-deltas]
  (->>
   group-deltas
   (filter (fn> [group-delta :- GroupDelta]
                (when (pos? (get (second group-delta) :delta))
                  [(first group-delta)
                   (get (second group-delta) :delta)])))))

;;; ## Node creation and removal
;; TODO remove :no-check when core.type understands assoc
(ann ^:no-check create-nodes
     [ComputeService EnvironmentMap GroupSpec AnyInteger
      -> (Seq TargetMap)])
(defn create-nodes
  "Create `count` nodes for a `group`."
  [compute-service environment group count]
  ((inst map TargetMap Node)
   (fn> [node :- Node] (assoc group :node node))
   (run-nodes
    compute-service group count
    (:user environment)
    nil
    (:provider-options environment nil))))

(ann remove-nodes [ComputeService GroupSpec
                   (HMap :mandatory {:nodes (Seq TargetMap) :all boolean})
                   -> nil])
(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all]}]
  (debugf "remove-nodes all %s nodes %s" all nodes)
  (if all
    (destroy-nodes-in-group compute-service (:group-name group))
    (doseq> [node :- TargetMap nodes]
            (destroy-node compute-service (:node node)))))

;;; # Node state tagging
(ann state-tag-name String)
(def state-tag-name "pallet/state")

(ann read-or-empty-map [String -> (Map Keyword Any)])
(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (assert-type-predicate (read-string s) keyword-map?)))

(ann set-state-for-node [String TargetMap -> nil])
(defn set-state-for-node
  "Sets the boolean `state-name` flag on `node`."
  [state-name node]
  (debugf "set-state-for-node %s" state-name)
  (when (taggable? (:node node))
    (debugf "set-state-for-node taggable")
    (let [current (read-or-empty-map (tag (:node node) state-tag-name))
          val (assoc current (keyword (name state-name)) true)]
      (debugf "set-state-for-node %s %s" state-tag-name (pr-str val))
      (tag! (:node node) state-tag-name (pr-str val)))))

(ann has-state-flag? [String -> [TargetMap -> boolean]])
(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [state-name]
  (fn [node]
    (debugf "has-state-flag %s %s" state-name (id (:node node)))
    (let [v (boolean
             (get
              (read-or-empty-map (tag (:node node) state-tag-name))
              (keyword (name state-name))))]
      (tracef "has-state-flag %s" v)
      v)))

;;; # Exception reporting
;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check phase-errors-in-results
     [(Nilable (NonEmptySeqable PhaseResult)) ->
      (Nilable (NonEmptySeqable PhaseResult))])
(defn phase-errors-in-results
  "Return a sequence of phase errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [results]
  (seq
   (concat
    (->>
     results
     ((inst map PhaseResult PhaseResult)
      (fn> [ar :- PhaseResult]
           ((inst update-in
                  PhaseResult (Nilable (NonEmptySeqable ActionResult)))
            ar [:result]
            (fn> [rs :- (NonEmptySeqable ActionResult)]
                 (seq (filter (fn> [r :- ActionResult]
                                   (get r :error))
                              rs))))))
     (mapcat (fn> [ar :- PhaseResult]
                  ((inst map PhaseResult PhaseResult)
                   (fn> [r :- PhaseResult]
                        (merge (dissoc ar :result) r))
                   (get ar :result)))))
    (filter (inst :error ActionErrorMap) results))))

;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check phase-errors
     [Session -> (Nilable (NonEmptySeqable PhaseResult))])
(defn phase-errors
  "Return a sequence of phase errors for an operation.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [result]
  ;; TO switch back to Keyword invocation when supported in core.typed
  (debugf "phase-errors %s" (vec (get result :results)))
  (phase-errors-in-results (get result :results)))

;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check phase-error-exceptions [Session -> (Seqable Throwable)])
(defn phase-error-exceptions
  "Return a sequence of exceptions from phase errors for an operation. "
  [result]
  (->> (phase-errors result)
       ((inst map Throwable PhaseResult)
        ((inst comp ActionErrorMap Throwable PhaseResult) :exception :error))
       (filter identity)))

;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check throw-phase-errors [Session -> nil])
(defn throw-phase-errors
  [result]
  (when-let [e (phase-errors result)]
    (throw
     (ex-info
      (str "Phase errors: "
           (string/join
            " "
            ((inst map (U String nil) PhaseResult)
             ((inst comp ActionErrorMap String PhaseResult) :message :error)
             e)))
      {:errors e}
      (first (phase-error-exceptions result))))))
