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

;;; ## Action Plan Execution

;;; # Execute action on a target node
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
(defn execute-action
  "Execute an action map within the context of the current session."
  [session action]
  (debugf "execute-action %s" (pr-str action))
  (let [executor (executor session)
        execute-status-fn (execute-status-fn session)]
    (assert executor "No executor in session")
    (assert execute-status-fn "No execute-status-fn in session")
    (debugf "execute-action executor %s" (pr-str executor))
    (debugf "execute-action execute-status-fn %s" (pr-str execute-status-fn))
    (let [rv (executor session action)]
      (debugf "execute-action rv %s" (pr-str rv))
      (assert (map? rv) (str "Action return value must be a map: " (pr-str rv)))
      (record (recorder session) rv)
      (execute-status-fn rv)
      rv)))

;; These should be part of the executor
      ;; session (parse-shell-result session rv)
      ;; TODO add retries, timeouts, etc

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

(ann ^:no-check execute [BaseSession TargetMap PlanFn -> PlanResult])
(defn execute
  "Execute a plan function on a target.

  Ensures that the session target is set, and that the script
  environment is set up for the target.

  Returns a channel, which will yield a result for plan-fn, a map
  with `:target`, `:return-value` and `:action-results` keys."
  [session target plan-fn]
  {:pre [(map? session)(map? target)(:node target)(fn? plan-fn)]}
  (let [r (in-memory-recorder)     ; a recorder for just this plan-fn
        session (-> session
                    (set-target target)
                    (set-recorder (juxt-recorder [r (recorder session)])))]
    (with-script-for-node target (plan-state session)
      (let [rv (plan-fn session)]
        {:action-results (results r)
         :return-value rv
         :target target}))))


;; Not sure this is worth having as a wrapper
;; (ann ^:no-check go-execute
;;      [BaseSession TargetMap PlanFn -> (ReadOnlyPort PlanResult)])
;; (defn go-execute
;;   "Execute a plan function on a target asynchronously.

;;   Ensures that the session target is set, and that the script
;;   environment is set up for the target.

;;   Returns a channel, which will yield a result for plan-fn, a map
;;   with `:target`, `:return-value` and `:action-results` keys."
;;   [session target plan-fn]
;;   {:pre [(map? session)(map? target)(:node target)(fn? plan-fn)]}
;;   (go-logged
;;    (execute session target plan-fn)))






(ann ^:no-check action-errors?
  [(Seqable (ReadOnlyPort ActionResult)) -> (Nilable PlanResult)])
(defn action-errors?
  "Check for errors reported by the sequence of channels.  This provides
  a synchronisation point."
  [chans]
  (->> chans
       (mapv (fn> [c :- (ReadOnlyPort ActionResult)]
               (timeout-chan c (* 5 60 1000))))
       async/merge
       (async/reduce
        (fn> [r :- (Nilable PlanResult)
              v :- (Nilable ActionResult)]
          (or r (and (nil? v) {:error {:timeout true}})
              (and (:error v) (select-keys v [:error]))))
        nil)))

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

;;; # Exception reporting
;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check phase-errors-in-results
     [(Nilable (NonEmptySeqable PlanResult)) ->
      (Nilable (NonEmptySeqable PlanResult))])
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


;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check phase-errors
     [Session -> (Nilable (NonEmptySeqable PlanResult))])
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
       ((inst map Throwable PlanResult)
        ((inst comp ActionErrorMap Throwable PlanResult) :exception :error))
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
            ((inst map (U String nil) PlanResult)
             ((inst comp ActionErrorMap String PlanResult) :message :error)
             e)))
      {:errors e}
      (first (phase-error-exceptions result))))))
