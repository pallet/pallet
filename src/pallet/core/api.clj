(ns pallet.core.api
  "API for execution of pallet plan functions."
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
            IncompleteGroupTargetMapSeq Keyword Phase PlanResult PhaseTarget
            PlanFn
            PlanState Result TargetMapSeq Session TargetMap TargetPlanResult
            User]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.async :refer [go-logged map-async timeout-chan]]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute
    :refer [compute-service? destroy-node destroy-nodes nodes run-nodes
            service-properties]]
   [pallet.compute.protocols :refer [ComputeService]]
   [pallet.core.api-impl :refer :all]
   [pallet.core.executor :as executor]
   [pallet.core.plan-state :refer [get-scopes]]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.core.session
    :refer [add-system-targets admin-user base-session? execute-status-fn
            executor plan-state recorder remove-system-targets set-recorder
            set-target]]
   [pallet.core.user :refer [*admin-user* obfuscated-passwords user?]]
   [pallet.execute :refer [parse-shell-result]]
   [pallet.node
    :refer [compute-service id image-user group-name primary-ip
            tag tag! taggable? terminated?]])
  (:import
   clojure.lang.IMapEntry
   pallet.compute.protocols.Node))

;;; ## Action Plan Execution

;;; # Execute action on a target node
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
(defn execute-action
  "Execute an action map within the context of the current session."
  [session action]
  (let [executor (executor session)
        target (:target session)
        user (:user session)]

    (debugf "execute-action %s" (pr-str action))
    (assert executor "No executor in session")
    (tracef "execute-action executor %s" (pr-str executor))

    (let [[rv e] (try
                   [(executor/execute executor target user action)]
                   (catch Exception e
                     (let [rv (:result (ex-data e))]
                       (when-not rv
                         ;; Exception isn't of the expected form, so
                         ;; just re-throw it.
                         (throw e))
                       [rv e])))]
      (debugf "execute-action rv %s" (pr-str rv))
      (assert (map? rv) (str "Action return value must be a map: " (pr-str rv)))
      (record (recorder session) rv)
      (when e
        (throw e))
      rv)))

(ann ^:no-check execute [BaseSession TargetMap PlanFn -> PlanResult])
(defn execute
  "Execute a plan function on a target.

Ensures that the session target is set, and that the script
environment is set up for the target.

Returns a map with `:target`, `:return-value` and `:action-results`
keys.

The result is also written to the recorder in the session."
  [session target plan-fn]
  {:pre [(map? session)
         (map? target)
         (:node target)
         (fn? plan-fn)
         ;; Reduce preconditions? or reduce magic by not having defaults?
         ;; TODO allow no recorder in session? default to null recorder?
         ;; TODO have a default executor?
         (recorder session)
         (executor session)]}
  (let [r (in-memory-recorder) ; a recorder for the scope of this plan-fn
        recorder (juxt-recorder [r (recorder session)])
        session (-> session
                    (set-target target)
                    (set-recorder recorder))]
    (with-script-for-node target (plan-state session)
      ;; We need the script context for script blocks in the plan
      ;; functions.

      (let [rv (try
                 (plan-fn session)
                 (catch Exception e
                   ;; Wrap the exception so we can return the
                   ;; accumulated results.
                   (throw (ex-info "Exception in plan-fn"
                                   {:action-results (results r)
                                    :return-value rv
                                    :target target}
                                   e))))]
        {:action-results (results r)
         :return-value rv
         :target target}))))


;;; TODO make sure these error reporting functions are relevant and correct

;;; # Exception reporting
;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check errors
     [(Nilable (NonEmptySeqable PlanResult)) ->
      (Nilable (NonEmptySeqable PlanResult))])
(defn errors
  "Return a sequence of errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [results]
  (seq
   (concat
    (->>
     results
     ((inst map PlanResult PlanResult)
      (fn> [ar :- PlanResult]
           ((inst update-in
                  PlanResult (Nilable (NonEmptySeqable ActionResult)))
            ar [:result]
            (fn> [rs :- (NonEmptySeqable ActionResult)]
                 (seq (filter (fn> [r :- ActionResult]
                                   (get r :error))
                              rs))))))
     (mapcat (fn> [ar :- PlanResult]
                  ((inst map PlanResult PlanResult)
                   (fn> [r :- PlanResult]
                        (merge (dissoc ar :result) r))
                   (get ar :result)))))
    (filter (inst :error ActionErrorMap) results))))


(ann error-exceptions
     [(Nilable (NonEmptySeqable PlanResult)) -> (Seqable Throwable)])
(defn error-exceptions
  "Return a sequence of exceptions from a sequence of errors for an operation."
  [errors]
  (->> errors
       ((inst map Throwable PlanResult)
        ((inst comp ActionErrorMap Throwable PlanResult) :exception :error))
       (filter identity)))

(ann throw-errors [(Nilable (NonEmptySeqable PlanResult)) -> nil])
(defn throw-phase-errors
  "Throw an exception if the errors sequence is non-empty."
  [errors]
  (when-let [e (seq errors)]
    (throw
     (ex-info
      (str "Errors: "
           (string/join
            " "
            ((inst map (U String nil) PlanResult)
             ((inst comp ActionErrorMap String PlanResult) :message :error)
             e)))
      {:errors e}
      (first (error-exceptions errors))))))







;;; These should be part of the executor

;; execute-status-fn (execute-status-fn session)
;; (assert execute-status-fn "No execute-status-fn in session")
;; (debugf "execute-action execute-status-fn %s" (pr-str execute-status-fn))
;; (execute-status-fn rv)
;; session (parse-shell-result session rv)
;; TODO add retries, timeouts, etc

;;; # Execute a phase on a target node
;; (ann stop-execution-on-error [ActionResult -> nil])
;; (defn stop-execution-on-error
;;   ":execute-status-fn algorithm to stop execution on an error"
;;   [result]
;;   (when (:error result)
;;     (debugf "Stopping execution %s" (:error result))
;;     (let [msg (-> result :error :message)]
;;       (throw (ex-info
;;               (str "Phase stopped on error" (if msg (str " - " msg)))
;;               {:error (:error result)
;;                :message msg}
;;               (get (get result :error) :exception))))))


;; ;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
;; (ann ^:no-check phase-errors
;;      [Session -> (Nilable (NonEmptySeqable PlanResult))])
;; (defn phase-errors
;;   "Return a sequence of errors for an operation.
;;    Each element in the sequence represents a failed action, and is a map,
;;    with :target, :error, :context and all the return value keys for the return
;;    value of the failed action."
;;   [session]
;;   ;; TO switch back to Keyword invocation when supported in core.typed
;;   (debugf "phase-errors %s" (vec (:results session)))
;;   (errors (:results session)))

;; ;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
;; (ann ^:no-check phase-error-exceptions [Session -> (Seqable Throwable)])
;; (defn phase-error-exceptions
;;   "Return a sequence of exceptions from phase errors for an operation. "
;;   [result]
;;   (->> (phase-errors result)
;;        ((inst map Throwable PlanResult)
;;         ((inst comp ActionErrorMap Throwable PlanResult) :exception :error))
;;        (filter identity)))

;; ;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
;; (ann ^:no-check throw-phase-errors [Session -> nil])
;; (defn throw-phase-errors
;;   [result]
;;   (when-let [e (phase-errors result)]
;;     (throw
;;      (ex-info
;;       (str "Phase errors: "
;;            (string/join
;;             " "
;;             ((inst map (U String nil) PlanResult)
;;              ((inst comp ActionErrorMap String PlanResult) :message :error)
;;              e)))
;;       {:errors e}
;;       (first (phase-error-exceptions result))))))


;;; # Node creation and removal

;;; Not sure here what the arguments should be; maybe a node-spec,
;;; and a set of roles to tag the nodes with.
;; (ann ^:no-check create-nodes
;;      [Session ComputeService User NodeSpec Tags AnyInteger -> (Seq TargetMap)])
;; (defn create-nodes
;;   "Create `count` nodes using `node-spec` to define the properties of the
;;   nodes, and setting `tags` on them.  `user` will be authorised on the node,
;;   if it has a public key."
;;   [session compute-service user node-spec tags count]
;;   {:pre [(base-session? session)
;;          (compute-service? compute-service)
;;          (user? user)]}
;;   ((inst map TargetMap Node)
;;    (fn> [node :- Node] (assoc group :node node))
;;    (let [targets (run-nodes compute-service node-spec user count)]
;;      (tag-nodes compute-service (map :node targets) tags)
;;      (add-system-targets session targets)
;;      targets)))

;; (ann remove-nodes [Session ComputeService TargetMapSeq
;;                    -> nil])
;; (defn remove-nodes
;;   "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
;;   are being removed."
;;   [session compute-service targets]
;;   (debugf "remove-nodes %s targets" (vector targets))
;;   (destroy-nodes compute-service (map (:node target) targets))
;;   (remove-system-targets session targets))
