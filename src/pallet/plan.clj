(ns pallet.plan
  "API for execution of pallet plan functions."
  (:require
   [clojure.core.async :refer [chan go]]
   [clojure.string :as string]
   [com.palletops.api-builder :refer [def-defn def-fn]]
   [com.palletops.api-builder.core :refer [arg-and-ref assert*]]
   [com.palletops.api-builder.stage :refer [add-sig-doc validate-optional-sig]]
   [pallet.core.api-builder :refer [defn-api defn-sig]]
   [pallet.core.context :refer [with-context with-context-update]]
   [pallet.core.executor :as executor]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.exception
    :refer [combine-exceptions compiler-exception domain-error?]]
   [pallet.node :refer [script-template validate-node]]
   [pallet.script :refer [with-script-context]]
   [pallet.session :as session
    :refer [BaseSession TargetSession
            base-session? recorder set-recorder set-target target-session?
            user]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.user :refer [User]]
   [pallet.utils.async
    :refer [ReadPort WritePort concat-chans go-try reduce-results]]
   [pallet.utils.multi :as multi
    :refer [add-method dispatch-every-fn dispatch-key-fn dispatch-map]]
   [schema.core :as schema :refer [maybe named optional-key validate]]
   [taoensso.timbre :refer [debugf tracef]]))

;;; # Action Plan Execution
(def PlanFn (schema/=> schema/Any BaseSession schema/Any))

(def Action ; defined here to avoid circular dependency
  {:action {:action-symbol clojure.lang.Symbol
            :impls clojure.lang.Atom
            (optional-key :options) {schema/Keyword schema/Any}}
   :args [schema/Any]
   (optional-key :options) {schema/Keyword schema/Any}})

(def Target
  "Defines a target for execution.  This is usually a node or a spec,
  but is open for extension."
  {schema/Keyword schema/Any})

(def ActionResult
  "The result of a single action.  The `:error` key is reserved for
  reporting failed actions (domain errors)."
  {(schema/optional-key :error) schema/Any
   (schema/optional-key :summary) (maybe String)
   schema/Keyword schema/Any})

(def PlanResult
  "The result of a plan function on a single target."
  {(optional-key :action-results) [ActionResult]
   (optional-key :return-value) schema/Any
   (optional-key :exception) Exception})

(def PlanTargetResult
  "The result of a plan function on a single target, labeled by target."
  (assoc PlanResult
    :target Target))

(def TargetResult
  (-> PlanResult
      (dissoc :action-results)
      (assoc (optional-key :action-results) [ActionResult]
             :target {schema/Keyword schema/Any})))

(def TargetPlan
  {:target schema/Any
   :plan-fn (schema/=> WritePort TargetSession)})

(def TargetPhase
  "A sequence of target plans, with an id for identifying the results."
  {:result-id {schema/Any schema/Any}
   :target-plans [TargetPlan]})

(def ^:internal AdornedPlanResult
  (assoc PlanResult schema/Keyword schema/Any))

(def ^:internal AdornedTargetResult
  (assoc TargetResult schema/Keyword schema/Any))

;;; ## Execute action on a target
(defn-sig ^:internal execute-action
  "Execute an action map within the context of the current session.
  If the action throws an exception, add it to the result, and throw a
  wrapped exception containing the results.  Throwing the exception will
  cease execution of the plan function (unless explicitly handled)."
  {:sig [[TargetSession Action :- ActionResult]]}
  [{:keys [target] :as session} action]
  {:pre [(validate User (user session))
         (validate Target target)]}
  (debugf "execute-action action %s" (pr-str action))
  (tracef "execute-action session %s" (pr-str session))
  (let [executor (session/executor session)]
    (tracef "execute-action executor %s" (pr-str executor))
    (assert executor "No executor in session")
    (let [[rv e] (try
                   [(executor/execute executor target action)]
                   (catch Exception e
                     [nil e]))
          [rv record?] (if e
                         (let [data (ex-data e)]
                           (if (contains? data :result)
                             [(:result data) true]
                             [nil nil]))
                         [rv true])]

      (tracef "execute-action rv %s" (pr-str rv))
      (when record?
        (assert* (map? rv) "Action return value must be a map: %s", rv)
        (let [rv (if (:record-all (action :options) true)
                   rv
                   (if (or (:error rv) e)
                     rv
                     (select-keys rv [:summary])))]
          (record (recorder session) rv)))
      (when e
        (throw e))
      rv)))

;;; ## Execute a Plan Function against a Target
(defmulti execute-plan*
  "Execute a plan function. Does not call any plan middleware.

  By default we execute the plan-fn in a context where actions are
  allowed.

  Suitable for use as a plan middleware handler function.  Should not
  be called directly.  This is an extension point for pallet, that
  could be used to define a new target type to execute against."
  (fn [session target plan-fn]
    {:pre [(map? target)]}
    (:target-type target ::node)))

;; Execute a plain plan function, with no node
(defmethod execute-plan* :group
  [session target plan-fn]
  {:return-value (plan-fn (set-target session target))})


;; Ensures that the session target is set, and that the script
;; environment is set up for the node.

;; Returns a map with `:target`, `:return-value` and `:action-results`
;; keys. May throw non-domain exceptions.

;; The result is also written to the recorder in the session.
(defmethod execute-plan* ::node
  [session target plan-fn]
  {:pre [(base-session? session)
         (validate-node target)
         (fn? plan-fn)
         ;; Reduce preconditions? or reduce magic by not having defaults?
         ;; TODO have a default executor?
         (session/executor session)]
   :post [(or (nil? %) (validate PlanResult %))]}
  (debugf "execute* %s %s" target plan-fn)
  (let [r (in-memory-recorder) ; a recorder for the scope of this plan-fn
        session (-> session
                    (set-target target)
                    (set-recorder (if-let [recorder (recorder session)]
                                    (juxt-recorder [r recorder])
                                    r)))]
    (assert (target-session? session) "target session created correctly")
    ;; We need the script context for script blocks in the plan
    ;; functions.
    (with-script-context (script-template target)
      (with-script-language :pallet.stevedore.bash/bash
        (debugf "execute-plan* %s" target)
        (let [[rv e] (try
                       [(plan-fn session)]
                       (catch Exception e
                         [nil e]))
              result {:action-results (results r)}
              result (if e
                       (assoc result :exception e)
                       (assoc result :return-value rv))]
          (if (or (nil? e) (domain-error? e))
            result
            ;; Wrap the exception so we can return the
            ;; accumulated results.
            (throw (ex-info "Exception in plan-fn"
                            (assoc result :target target)
                            e))))))))

(defn-api execute-plan
  "Using the session, execute plan-fn on target. Uses any plan
  middleware defined on the plan-fn.  Results are recorded by any
  recorder in the session, as well as being returned."
  {:sig [[BaseSession Target PlanFn :- (maybe PlanTargetResult)]]}
  [session target plan-fn]
  (with-context {:target ((some-fn :group-name :id :primary-ip) target)}
    (let [{:keys [middleware]} (meta plan-fn)
          result (if middleware
                   (middleware session target plan-fn)
                   (execute-plan* session target plan-fn))]
      (if result
        (assoc result :target target)))))

;;; ## Execute Plan Functions on Mulitple Targets
(defn-sig execute-plans*
  "Using the executor in `session`, execute phase on all targets.
  The targets are executed in parallel, each in its own thread.  A
  single [result, exception] tuple will be written to the channel ch,
  where result is a sequence of results for each target, and exception
  is a composite exception of any thrown exceptions.

  Does not call phase middleware.  Does call plan middleware.  Suitable
  as the handler for a phase-middleware."
  {:sig [[BaseSession [TargetPlan] WritePort :- ReadPort]]}
  [session target-plans ch]
  (let [c (chan)]
    (->
     (map (fn execute-plan-fns [{:keys [target plan-fn]}]
            (go
              (try
                (if-let [r (execute-plan session target plan-fn)]
                  (merge {:results [r]}
                         (select-keys r [:exception]))
                  (debugf "execute-plan* no result"))
                (catch Throwable e
                  (let [data (ex-data e)]
                    (if (contains? data :action-results)
                      {:results [data] :exception e}
                      {:exception e}))))))
          target-plans)
     (concat-chans c))
    (reduce-results c "execute-plans* failed" ch)))

(defn-api execute-plans
  "Execute plan functions on targets.  Write a result tuple to the
  channel, ch.  Targets are grouped by phase-middleware, and phase
  middleware is called.  plans are executed in parallel.
  `target-plans` is a sequence of target, plan-fn tuples."
  {:sig [[BaseSession [TargetPlan] WritePort :- ReadPort]]}
  [session target-plans ch]
  (debugf "execute-plans %s target-plans" (count target-plans))
  (let [mw-targets (group-by (comp :phase-middleware meta :plan-fn)
                             target-plans)
        c (chan)]
    (debugf "execute-plans %s distinct middleware" (count mw-targets))
    (concat-chans
     (for [[mw target-plans] mw-targets]
       (if mw
         (mw session target-plans c)
         (execute-plans* session target-plans c)))
     c)
    (reduce-results c "execute-plans failed" ch)))

(defn-api execute-target-phase
  "Execute plans, merging the result-id map into the result of each
  plan-fn.  Write a result-exception map to the channel, ch."
  {:sig [[BaseSession TargetPhase WritePort :- ReadPort]]}
  [session {:keys [result-id target-plans] :as target-phase} ch]
  (with-context {:result-id result-id}
    (debugf "execute-target-phase %s target plans" (count target-plans))
    (let [c (chan)]
      (execute-plans session target-plans c)
      (go-try ch
        (if-let [{:keys [results exception] :as r} (<! c)]
          (let [r (update-in r [:results]
                             (fn [results]
                               (mapv #(merge result-id %) results)))
                e (combine-exceptions
                   [exception] "execute-target-phase failed" r)]
            (>! ch (cond-> r e (assoc :exception e))))
          (>! ch {:exception (ex-info "No result from execute-plans" {})}))))))

;;; # Exception reporting
(defn-api plan-errors
  "Return a plan result containing just the errors in the action results
  and any exception information.  If there are no errors, return nil."
  {:sig [[(schema/either AdornedPlanResult AdornedTargetResult)
          :- (maybe (schema/either AdornedPlanResult AdornedTargetResult))]]}
  [plan-result]
  (let [plan-result (update-in plan-result [:action-results]
                               #(filter :error %))]
    (if (or (:exception plan-result)
            (seq (:action-results plan-result)))
      plan-result)))

(defn-api errors
  "Return a sequence of errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a
   map, with :target, :error, :context and all the return value keys
   for the return value of the failed action."
  {:sig [[[AdornedTargetResult] :- [AdornedTargetResult]]]}
  [results]
  (seq (remove nil? (map plan-errors results))))

(defn-api error-exceptions
  "Return a sequence of exceptions from a sequence of results."
  {:sig [[[AdornedTargetResult] :- [Throwable]]]}
  [results]
  (seq (remove nil? (map :exception results))))

(defn-api throw-errors
  "Throw an exception if there is any exception in results."
  {:sig [[[AdornedTargetResult] :- (schema/eq nil)]]}
  [results]
  (when-let [e (seq (remove nil? (map :exception results)))]
    (throw
     (combine-exceptions e))))

;;; # Plan functions

;;; The phase context is used in actions and crate functions. The
;;; phase context automatically sets up a context, which is available
;;; (for logging, etc) at phase execution time.

(defmacro plan-context
  "Defines a block with a context that is automatically added."
  [context-name & body]
  `(with-context-update [[:plan] (fnil conj []) ~context-name]
     ~@body))

(defn- final-fn-sym?
  "Predicate to match the final function symbol in a form."
  [sym form]
  (loop [form form]
    (when (sequential? form)
      (let [s (first form)]
        (if (and (symbol? s) (= sym (symbol (name s))))
          true
          (recur (last form)))))))

(defn add-plan-context
  "Adds a plan-context aroiund the body of the function."
  [{:keys [name] :as defn-map}]
  ;; if the final function call is recursive, then don't add a
  ;; plan-context, so that just forwarding different arities only
  ;; gives one log entry/event, etc.
  (letfn [(add-body-context [{:keys [body] :as arity}]
            (if (or (not name) (final-fn-sym? name body))
              arity
              (assoc arity :body
                     [`(plan-context '~(symbol (str (clojure.core/name name)))
                         ~@body)])))]
    (update-in defn-map [:arities]
               (fn [arities] (map add-body-context arities)))))

(defn check-plan-arguments
  "Check arguments for at least one (session) argument, and add a
  runtime check that it is passed a TargetSession"
  [{:keys [name] :as defn-map}]
  (doseq [{:keys [args body]} (:arities defn-map)]
    (when-not (pos? (count args))
      (throw
       (compiler-exception
        args "defplan requires at least a session argument" {}))))
  (letfn [(add-session-validation [{:keys [args body] :as arity}]
            (let [args-refs (map arg-and-ref args)
                  session (-> args-refs first second)]
              (-> arity
                  (assoc :args (mapv first args-refs))
                  (update-in [:conditions :pre]
                             (fnil conj [])
                             `(validate (named TargetSession ~(str session))
                                        ~session)))))]
    (update-in defn-map [:arities]
               (fn [arities] (map add-session-validation arities)))))


(def-fn plan-fn
  "Create a plan function from a sequence of plan function invocations.

   eg. (plan-fn [session]
         (file session \"/some-file\")
         (file session \"/other-file\"))

   This generates a new plan function, and adds code to verify the state
   around each plan function call.

  The plan-fn can be optionally named, as for `fn`."
  [add-plan-context check-plan-arguments])

(def-defn defplan
  "Define a plan function. Assumes the first argument is a session map.
  Adds a plan context around the function body.  Adds checking for at
  least one (session) argument."
  [add-plan-context check-plan-arguments (validate-optional-sig) (add-sig-doc)])

;;; Multi-method for plan functions
(defmacro defmulti-plan
  "Declare a multimethod for plan functions.  Does not have defonce semantics.
  Methods will automatically be wrapped in a plan-context."
  {:arglists '([name docstring? attr-map? dispatch-fn
                {:keys [hierarchy] :as options}])
   :api :plan}
  [name & args]
  (let [{:keys [name dispatch options]} (multi/name-with-attributes name args)
        {:keys [hierarchy]} options
        args (first (filter vector? dispatch))
        f (gensym "f")
        m (gensym "m")]
    `(let [~f (dispatch-key-fn
               ~dispatch
               {~@(if hierarchy `[:hierarchy ~hierarchy]) ~@[]
                :name '~name})
           ~m (dispatch-map ~f)]
       ~(with-meta
          `(defn ~name
             {::dispatch (atom ~m)
              :arglists '~[args]}
             [& [~@args :as argv#]]
             (multi/check-arity ~name ~(count args) (count argv#))
             (~f (:methods @(-> #'~name meta ::dispatch)) argv#))
          (meta &form)))))

(defmacro defmulti-every
  "Declare a multimethod where method dispatch values have to match
  all of a sequence of predicates.  Each predicate will be called with the
  dispatch value, and the argument vector.  When multiple dispatch methods
  match, the :selector option will be called on the sequence of matching
  dispatches, and should return the selected match.  :selector defaults
  to `first`.

  Use defmethod-plan to add methods to defmulti-every."
  {:arglists '([name docstring? attr-map? dispatch-fns
                {:keys [selector] :as options}])
   :api :plan}
  [name & args]
  (let [{:keys [name dispatch options]} (multi/name-with-attributes name args)
        {:keys [selector]} options
        args (or (first (:arglists (meta name))) '[& args])
        f (gensym "f")
        m (gensym "m")]
    `(let [~f (dispatch-every-fn
               ~dispatch
               {~@(if selector `[:selector ~selector]) ~@[]
                :name '~name})
           ~m (dispatch-map ~f)]
       ~(with-meta
          `(defn ~name
             {::dispatch (atom ~m)
              :arglists '~[args]}
             [& [~@args :as argv#]]
             (~f (:methods @(-> #'~name meta ::dispatch)) argv#))
          (meta &form)))))

(defmacro defmethod-plan
  {:api :plan}
  [multifn dispatch-val args & body]
  (letfn [(sanitise [v]
            (string/replace (str v) #":" ""))]
    (when-not (resolve multifn)
      (throw (compiler-exception
              &form (str "Could not find defmulti-plan " multifn))))
    `(swap!
      (-> #'~multifn meta ::dispatch)
      add-method
      ~dispatch-val
      ~(with-meta
         `(fn [~@args]
            (plan-context
                '~(symbol (str (name multifn) "-" (sanitise dispatch-val)))
              ~@body))
         (meta &form)))))
