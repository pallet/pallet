(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.core.async :as async :refer [chan close! go put! thread <! >!]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.core.typed.async :refer [ReadOnlyPort]]
   [pallet.core.type-annotations]
   [pallet.core.types                   ; before any protocols
    :refer [assert-not-nil assert-type-predicate keyword-map?
            Action ActionErrorMap ActionResult BaseSession EnvironmentMap
            ErrorMap ExecSettings ExecSettingsFn GroupName GroupSpec
            IncompleteTargetMapSeq Keyword Phase PhaseResult PhaseTarget PlanFn
            PlanState Result TargetMapSeq Session TargetMap TargetPhaseResult
            User]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.async :refer [go-logged map-async timeout-chan]]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :refer [destroy-node destroy-nodes-in-group nodes run-nodes
                           service-properties]]
   [pallet.compute.protocols :refer [ComputeService]]
   [pallet.core.api-impl :refer :all]
   [pallet.core.plan-state :refer [get-scopes]]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.core.session
    :refer [add-system-targets admin-user execute-status-fn executor plan-state
            recorder remove-system-targets set-recorder set-target]]
   [pallet.core.user :refer [*admin-user* obfuscated-passwords]]
   [pallet.execute :refer [parse-shell-result]]
   [pallet.node
    :refer [compute-service id image-user group-name primary-ip
            tag tag! taggable? terminated?]]
   [pallet.session.verify :refer [add-session-verification-key check-session]]
   [pallet.stevedore :refer [with-source-line-comments]]
   [pallet.sync :refer [sync-phase*]])
  (:import
   clojure.lang.IMapEntry
   pallet.compute.protocols.Node))

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
                    -> IncompleteTargetMapSeq])
(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a sequence that contains a node-map for each
  matching node."
  [compute-service groups]
  (let [nodes (remove terminated? (nodes compute-service))]
    (tracef "service-state %s" (vec nodes))
    (seq (remove nil? (map (node->node-map groups) nodes)))))

(ann ^:no-check service-groups [ComputeService -> (Seqable GroupSpec)])
(defn service-groups
  "Query the available nodes in a `compute-service`, returning a group-spec
  for each group found."
  [compute-service]
  (->> (nodes compute-service)
       (remove terminated?)
       (map group-name)
       (map (fn> [gn :- GroupName] {:group-name gn}))
       (map (fn> [m :- (HMap :mandatory {:group-name GroupName})]
              ((inst vary-meta
                     (HMap :mandatory {:group-name GroupName})
                     Keyword Keyword)
               m assoc :type :pallet.api/group-spec)))))

;;; ## Action Plan Execution

;;; # Execute action on a target node
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
(defn execute-action
  "Execute an action map within the context of the current session."
  [session action]
  (debugf "execute-action %s" (pr-str action))
  (let [executor (executor session)
        execute-status-fn (execute-status-fn session)
        _ (debugf "execute-action executor %s" (pr-str executor))
        _ (debugf "execute-action execute-status-fn %s"
                  (pr-str execute-status-fn))
        _ (assert executor "No executor in session")
        _ (assert execute-status-fn "No execute-status-fn in session")
        ;; TODO use destructuring when core.typed can grok it
        rv (executor session action)
        ;; [rv _] rrv
        out (:out rv)
        _ (debugf "execute-action rv %s" (pr-str rv))
        _ (assert (map? rv)
                  (str "Action return value must be a map: " (pr-str rv)))
        session (parse-shell-result session rv)]
    ;; TODO add retries, timeouts, etc
    (record (recorder session) rv)
    (execute-status-fn rv)
    rv))


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

(ann phase-args [Phase -> (Nilable (Seqable Any))])
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

;; ;; TODO remove :no-check when core.typed understands build a Map type properly
;; (ann ^:no-check action-plan
;;      [TargetMapSeq EnvironmentMap Phase ExecSettingsFn
;;       (U (Value :server) (Value :group))
;;       PhaseTarget -> [PlanState -> TargetPhaseResult]])
;; (defn action-plan
;;   "Build the action plan for the specified `plan-fn` on the given `node`, within
;;   the context of the `service-state`. The `plan-state` contains all the
;;   settings, etc, for all groups. `target-map` is a map for the session
;;   describing the target.

;;   Return a channel with the target phase result map."
;;   [service-state environment phase execution-settings-f target-kw target
;;    result-ch]
;;   {:pre [(map? target)
;;          (or (nil? environment) (map? environment))]}
;;   (let [plan-fn (target-phase target phase)
;;         args (phase-args phase)
;;         phase-kw (phase-kw phase)]
;;     (assert (and (not (map? plan-fn)) (fn? plan-fn))
;;             "plan-fn should be a function")
;;     (fn> action-plan [plan-state :- PlanState]
;;          (let [{:keys [user executor execute-status-fn]}
;;                (execution-settings-f environment target)]
;;            (with-session
;;              (add-session-verification-key
;;               (merge {:user (get environment :user)}
;;                      {:service-state service-state
;;                       :plan-state plan-state
;;                       :environment environment
;;                       target-kw target
;;                       :executor executor
;;                       :execute-status-fn execute-status-fn}))
;;              (try
;;                (apply plan-fn args)
;;                (close! result-ch)
;;                (catch Throwable e
;;                  (put! result-ch
;;                        [{:error {:type :exception :exception e}}
;;                         (:plan-state (session))])
;;                  (close! result-ch))))))))

;; ;; TODO remove no-check when commons is type checked
;; (ann ^:no-check target-action-plan
;;      [TargetMapSeq PlanState EnvironmentMap Phase ExecSettingsFn TargetMap
;;       -> [PlanState -> TargetPhaseResult]])
;; (defmulti target-action-plan
;;   "Build action plans for the specified `phase` on all nodes or groups in the
;;   given `target`, within the context of the `service-state`. The `plan-state`
;;   contains all the settings, etc, for all groups."
;;   (fn target-action-plan
;;     [service-state plan-state environment phase execution-settings-f target
;;      result-ch]
;;     (tracef "target-action-plan %s" (:target-type target :node))
;;     (:target-type target :node)))

;; (defmethod target-action-plan :node
;;   [service-state plan-state environment phase execution-settings-f target
;;    result-ch]
;;   {:pre [target (:node target) phase]}
;;   (fn [plan-state]
;;     (logutils/with-context [:target (get (get target :node) primary-ip)]
;;       (with-script-for-node target plan-state
;;         ((action-plan
;;           service-state
;;           environment
;;           phase
;;           execution-settings-f
;;           :server target
;;           result-ch)
;;          plan-state)))))

;; (defmethod target-action-plan :group
;;   [service-state plan-state environment phase execution-settings-f group
;;    result-ch]
;;   {:pre [group]}
;;   (fn [plan-state]
;;     (logutils/with-context [:target (-> group :group-name)]
;;       ((action-plan
;;         service-state
;;         environment
;;         phase
;;         execution-settings-f
;;         :group group
;;         result-ch)
;;        plan-state))))

;; (ann execute-phase-on-target
;;      (Fn [TargetMapSeq PlanState EnvironmentMap Phase ExecSettingsFn TargetMap
;;           -> TargetPhaseResult]
;;          [Phase TargetMap -> TargetPhaseResult]))
;; (defn execute-phase-on-target
;;   "Execute a phase on a single target."
;;   ([service-state plan-state environment phase execution-settings-f target-map]
;;      (let [result-ch (chan)
;;            f (target-action-plan
;;               service-state plan-state environment phase execution-settings-f
;;               target-map result-ch)
;;            timeout-ms (* 5 60 1000)
;;            r (async/reduce
;;               (fn [m [r plan-state]]
;;                 (-> m
;;                     (update-in [:result] (fnil conj []) r)
;;                     (assoc :plan-state plan-state)))
;;               {:target target-map
;;                :phase (phase-kw phase)
;;                :phase-args (phase-args phase)}
;;               (timeout-chan result-ch timeout-ms))]
;;        (thread
;;         (f plan-state))
;;        (debugf "action-plan result %s" r)
;;        r))
;;   ([phase target-map]
;;      (execute-phase-on-target
;;       [target-map] {} {:user *admin-user*}
;;       phase (environment-execution-settings) target-map)))



;; (defn target-phase-fn
;;   "Return the phase function for target and phase.
;;   The phase function will have no arguments."
;;   [target phase]
;;   (debugf "target-phase-fn %s" (phase-kw phase))
;;   (debugf "target-phase-fn target %s" target)
;;   (let [phase-kw (phase-kw phase)
;;         phase-args (phase-args phase)
;;         f (or (-> target :phases phase-kw)
;;               (constantly nil))]
;;     (debugf "target-phase-fn f %s" f)
;;     (fn synced-phase []
;;       (apply f phase-args))))

;; (defn target-phase-fn
;;   "Return a phase synchronised function for target and phase.
;;   The phase function will take a sync-service as an argument, and
;;   return a completion channel."
;;   [target phase]
;;   (debugf "target-phase-fn %s" (phase-kw phase))
;;   (debugf "target-phase-fn target %s" target)
;;   (let [phase-kw (phase-kw phase)
;;         phase-args (phase-args phase)
;;         f (or (-> target :phases phase-kw)
;;               (constantly nil))]
;;     (debugf "target-phase-fn f %s" f)
;;     (fn synced-phase []
;;       (debugf "synced-phase %s" phase-kw)
;;       (debugf "synced-phase %s %s" f phase-args)
;;       (sync-phase*
;;        sync-service phase target {}
;;        (fn phase-wrapper []
;;          (debugf "synced-phase %s %s %s" phase-kw f phase-args)
;;          (with-session session
;;            (with-script-for-node target (:plan-state session)
;;              (apply f phase-args))))))))

;; (defn target-fn
;;   "Return a phase synchronised function for target and sequence of
;;   phase functions.  The phase function will take a sync-service as an
;;   argument."
;;   [target phase-fns]
;;   (fn [sync-service session]
;;     (debugf "target-fn")
;;     (debugf "target-fn for %s phases" (count phase-fns))
;;     (go-logged
;;      (let [completion-ch (chan)]
;;        (loop [fs phase-fns]
;;          (when-let [f (first fs)]
;;            (debugf "target-fn call phase %s" f)
;;            (let [{:keys [state] :as leave} (<! (f sync-service session))]
;;              (debugf "target-fn phase returned %s" leave)
;;              (if (= :continue state)
;;                (recur (rest fs))
;;                leave))))))))

;; (defn session-for
;;   [target service-state {:keys [recorder plan-state sync-service
;;                                 executor execute-status-fn]
;;                          :as components}]
;;   (merge
;;    components
;;    {:user (get-scopes
;;            plan-state
;;            (merge {:group (:group-name target)
;;                    :universe true}
;;                   (if-let [node (:node target)]
;;                     {:host (id node)
;;                      :service (compute-service node)}))
;;            :user)}
;;    {(:target-type target :node) target
;;     :service-state service-state}))

;; (defn session-target
;;   "Returns a target keyword, value tuple for the target"
;;   [target]
;;   [(if (:node target) :server :group) target])

(ann ^:no-check execute [BaseSession TargetMap PlanFn -> (ReadOnlyPort PhaseResult)])
(defn execute
  "Execute a plan function on a target.

  Ensures that the session target is set, and that the script
  environment is set up for the target.

  Returns a channel, which will yield a result for plan-fn, a map
  with `:target`, `:return-value` and `:action-results` keys."
  [session target plan-fn]
  (go-logged
   (let [r (in-memory-recorder)     ; a recorder for just this plan-fn
         session (-> session
                     (set-target target)
                     (set-recorder (juxt-recorder [r (recorder session)])))]
     (with-script-for-node target (plan-state session)
       (let [rv (plan-fn session)]
         {:action-results (results r)
          :return-value rv
          :target target})))))

;; (ann phase-result
;;   [TargetMap (Seqable (ReadOnlyChan ActionResult)) -> (Nilable PhaseResult)])
;; (defn phase-result
;;   "Return a Phase result"
;;   [chans]
;;   (->> chans
;;        (mapv (fn> [c :- (ReadOnlyChan ActionResult)]
;;                (timeout-chan c (* 5 60 1000))))
;;        async/merge
;;        (async/reduce
;;         (fn> [r :- (Nilable (U Boolean ActionResult))
;;               v :- (Nilable ActionResult)]
;;           (or r (nil? v) (and (:error v) v)))
;;         nil)))

(ann ^:no-check action-errors?
  [(Seqable (ReadOnlyPort ActionResult)) -> (Nilable PhaseResult)])
(defn action-errors?
  "Check for errors reported by the sequence of channels.  This provides
  a synchronisation point."
  [chans]
  (->> chans
       (mapv (fn> [c :- (ReadOnlyPort ActionResult)]
               (timeout-chan c (* 5 60 1000))))
       async/merge
       (async/reduce
        (fn> [r :- (Nilable PhaseResult)
              v :- (Nilable ActionResult)]
          (or r (and (nil? v) {:error {:timeout true}})
              (and (:error v) (select-keys v [:error]))))
        nil)))

;; (defn execute
;;   "Execute matching targets and phase functions."
;;   [target-phase-fns service-state
;;    {:keys [recorder plan-state sync-service
;;                             executor execute-status-fn]
;;                      :as components}]
;;   (doseq [[target phase-fn] target-phase-fns]
;;     (go-logged
;;      (with-session (session-for target service-state components)
;;        (phase-fn sync-service)))))


;;; # Node state tagging
(ann state-tag-name String)
(def state-tag-name "pallet/state")

(ann read-or-empty-map [String -> (Map Keyword Any)])
(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (assert-type-predicate (read-string s) keyword-map?)))

(ann set-state-for-target [String TargetMap -> nil])
(defn set-state-for-target
  "Sets the boolean `state-name` flag on `target`."
  [state-name target]
  (debugf "set-state-for-target %s" state-name)
  (when (taggable? (:node target))
    (debugf "set-state-for-target taggable")
    (let [current (read-or-empty-map (tag (:node target) state-tag-name))
          val (assoc current (keyword (name state-name)) true)]
      (debugf "set-state-for-target %s %s" state-tag-name (pr-str val))
      (tag! (:node target) state-tag-name (pr-str val)))))

(ann has-state-flag? [String TargetMap -> boolean])
(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [state-name target]
  (debugf "has-state-flag? %s %s" state-name (id (:node target)))
  (let [v (boolean
           (get
            (read-or-empty-map (tag (:node target) state-tag-name))
            (keyword (name state-name))))]
    (tracef "has-state-flag? %s" v)
    v))



;;; ## Calculation of node count adjustments
(def-alias NodeDeltaMap (HMap :mandatory {:actual AnyInteger
                                          :target AnyInteger
                                          :delta AnyInteger}))
(def-alias GroupDelta '[GroupSpec NodeDeltaMap])
(def-alias GroupDeltaSeq (Seqable GroupDelta))

(ann group-delta [TargetMapSeq GroupSpec -> NodeDeltaMap])
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

(ann group-deltas [TargetMapSeq (Nilable (Seqable GroupSpec)) -> GroupDeltaSeq])
(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  ((inst map GroupDelta GroupSpec)
   (juxt
    (inst identity GroupSpec)
    (fn> [group :- GroupSpec]
      (group-delta (filter
                    (fn> [t :- TargetMap]
                      (node-in-group? (get t :node) group))
                    targets)
                   group)))
   groups))

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
(def-alias GroupNodesForRemoval
  (HMap :mandatory
        {:targets (NonEmptySeqable TargetMap)
         :all boolean}))

(ann ^:no-check nodes-to-remove
     [TargetMapSeq GroupDeltaSeq
      -> (Seqable '[GroupSpec GroupNodesForRemoval])])
(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [targets group-deltas]
  (letfn> [pick-servers :- [GroupDelta
                            -> '[GroupSpec
                                 (HMap :mandatory
                                       {:targets (NonEmptySeqable TargetMap)
                                        :all boolean})]]
           ;; TODO revert to destructuring when supported by core.typed
           (pick-servers [group-delta]
             (let
                 [group ((inst first GroupSpec) group-delta)
                  dm ((inst second NodeDeltaMap) group-delta)
                  target (get dm :target)
                  delta (get dm :delta)]
               (vector
                group
                {:targets (take (- delta)
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
     [Session ComputeService GroupSpec AnyInteger -> (Seq TargetMap)])
(defn create-nodes
  "Create `count` nodes for a `group`."
  [session compute-service group count]
  ((inst map TargetMap Node)
   (fn> [node :- Node] (assoc group :node node))
   (let [targets (run-nodes
                  compute-service group count
                  (admin-user session)
                  nil
                  (get-scopes
                   (plan-state)
                   {:provider (:provider (service-properties compute-service))
                    ;; :service ()
                    }
                   [:provider-options]))]
     (add-system-targets session targets)
     targets)))

(ann remove-nodes [Session ComputeService GroupSpec GroupNodesForRemoval
                   -> nil])
(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [session compute-service group removals]
  (let [all (:all removals)
        targets (:targets removals)]
    (debugf "remove-nodes all %s targets %s" all (vector targets))
    (remove-system-targets session targets)
    (if all
      (destroy-nodes-in-group compute-service (:group-name group))
      (doseq> [node :- TargetMap targets]
        (destroy-node compute-service (:node node))))))

(ann create-group-nodes
  [Session ComputeService (Seqable '[GroupSpec NodeDeltaMap]) ->
   (Seqable (ReadOnlyPort '[(Nilable (Seqable TargetMap))
                            (Nilable (ErrorMap '[GroupSpec NodeDeltaMap]))]))])
(defn create-group-nodes
  "Create nodes for groups."
  [session compute-service group-counts]
  (debugf "create-group-nodes %s %s" compute-service (vec group-counts))
  (map-async
   (fn> [v :- '[GroupSpec NodeDeltaMap]]
     (create-nodes session compute-service (first v) (:delta (second v))))
   group-counts
   (* 5 60 1000)))

(ann remove-group-nodes
  [Session ComputeService (Seqable '[GroupSpec GroupNodesForRemoval]) -> Any])
(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [session compute-service group-nodes]
  (debugf "remove-group-nodes %s" group-nodes)
  (map-async
   (fn> [x :- '[GroupSpec GroupNodesForRemoval]]
     (remove-nodes session compute-service (first x) (second x)))
   group-nodes
   (* 5 60 1000)))


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
