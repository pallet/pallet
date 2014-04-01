(ns pallet.plan
  "API for execution of pallet plan functions."
  (:require
   [clojure.core.async :as async :refer [<!! chan]]
   [taoensso.timbre :refer [debugf tracef]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [clojure.string :as string]
   [com.palletops.log-config.timbre :refer [with-context-update]]
   [pallet.core.executor :as executor]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.exception
    :refer [combine-exceptions compiler-exception domain-error?]]
   [pallet.node :refer [script-template validate-node]]
   [pallet.script :refer [with-script-context]]
   [pallet.session
    :refer [base-session base-session? executor plan-state recorder
            set-executor set-recorder set-target target-session? user
            target-session]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.user :refer [obfuscated-passwords user?]]
   [pallet.utils :refer [local-env map-arg-and-ref]]
   [pallet.utils.async
    :refer [concat-chans map-thread reduce-concat-results reduce-results
            WritePort]]
   [pallet.utils.multi :as multi
    :refer [add-method dispatch-every-fn dispatch-key-fn dispatch-map]]
   [schema.core :as schema :refer [check optional-key validate]]))

;;; # Action Plan Execution

(def Target {schema/Keyword schema/Any})

(def action-result-map
  {(schema/optional-key :error) schema/Any ; used to report action
                                           ; domain errors
   schema/Keyword schema/Any})

(def plan-result-map
  {(optional-key :action-results) [action-result-map]
   (optional-key :return-value) schema/Any
   (optional-key :exception) Exception})

(def plan-exception-map
  (assoc plan-result-map
    :target schema/Any))

(def PlanResult
  (assoc plan-result-map
    :target Target))

;;; ## Execute action on a target
(defn execute-action
  "Execute an action map within the context of the current session."
  [{:keys [target] :as session} action]
  {:pre [(target-session? session)
         (user? (user session))
         (map? target)]
   :post [(validate action-result-map %)]}
  (debugf "execute-action action %s" (pr-str action))
  (tracef "execute-action session %s" (pr-str session))
  (let [executor (executor session)]
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
        (assert (map? rv)
                (str "Action return value must be a map: " (pr-str rv)))
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
  be called directly."
  (fn [session target plan-fn]
    {:pre [(map? target)]}
    (:target-type target ::node)))

;; Execute a plain plan function, with no node
(defmethod execute-plan* :group
  [session target plan-fn]
  {:return-value (plan-fn session)})


;; Ensures that the session target is set, and that the script
;; environment is set up for the node.

;; Returns a map with `:target`, `:return-value` and `:action-results`
;; keys.

;; The result is also written to the recorder in the session.
(defmethod execute-plan* ::node
  [session target plan-fn]
  {:pre [(base-session? session)
         (validate-node target)
         (fn? plan-fn)
         ;; Reduce preconditions? or reduce magic by not having defaults?
         ;; TODO have a default executor?
         (executor session)]
   :post [(or (nil? %) (validate plan-result-map %))]}
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
        (debugf "execute* %s" target)
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

(def ^:internal target-result-map
  (-> plan-result-map
      (dissoc :action-results)
      (assoc (optional-key :action-results) [action-result-map]
             :target {schema/Keyword schema/Any})))

(defn execute-plan
  "Using the session, execute plan-fn on target. Uses any plan
  middleware defined on the plan-fn.  Results are recorded by any
  recorder in the session, as well as being returned."
  [session target plan-fn]
  {:pre [(fn? plan-fn)
         (map? target)]
   :post [(or (nil? %) (validate PlanResult %))]}
  (let [{:keys [middleware]} (meta plan-fn)
        result (if middleware
                 (middleware session target plan-fn)
                 (execute-plan* session target plan-fn))]
    (if result
      (assoc result :target target))))

;;; ## Execute Plan Functions on Mulitple Targets
(def TargetPlan
  {:target schema/Any
   :plan-fn (schema/=> WritePort target-session)})

(defn execute-plans*
  "Using the executor in `session`, execute phase on all targets.
  The targets are executed in parallel, each in its own thread.  A
  single [result, exception] tuple will be written to the channel ch,
  where result is a sequence of results for each target, and exception
  is a composite exception of any thrown exceptions.

  Does not call phase middleware.  Does call plan middleware.  Suitable
  as the handler for a phase-middleware."
  [session target-plans ch]
  (let [c (chan)]
    (->
     (map-thread (fn execute-plan-fns [{:keys [target plan-fn]}]
                   (try
                     [(execute-plan session target plan-fn)]
                     (catch Exception e
                       (let [data (ex-data e)]
                         (if (contains? data :action-results)
                           [data e]
                           [nil e])))))
                 target-plans)
     (concat-chans c))
    (reduce-results c "execute-plans* failed" ch)))

(defn execute-plans
  "Execute plan functions on targets.  Write a result tuple to the
  channel, ch.  Targets are grouped by phase-middleware, and phase
  middleware is called.  plans are executed in parallel.
  `target-plans` is a sequence of target, plan-fn tuples."
  [session target-plans ch]
  {:pre [(validate base-session session)
         (validate [TargetPlan] target-plans)
         (validate WritePort ch)]}
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
    (reduce-concat-results c "execute-plans failed" ch)))

;;; # Exception reporting
(def ^:internal adorned-plan-result-map
  (assoc plan-result-map schema/Keyword schema/Any))
(def ^:internal adorned-target-result-map
  (assoc target-result-map schema/Keyword schema/Any))

(defn plan-errors
  "Return a plan result containing just the errors in the action results
  and any exception information.  If there are no errors, return nil."
  [plan-result]
  {:pre [(validate
          (schema/either adorned-plan-result-map adorned-target-result-map)
          plan-result)]
   :post [(or
           (nil? %)
           (validate
            (schema/either adorned-plan-result-map adorned-target-result-map)
            plan-result))]}
  (let [plan-result (update-in plan-result [:action-results]
                               #(filter :error %))]
    (if (or (:exception plan-result)
            (seq (:action-results plan-result)))
      plan-result)))


(defn errors
  "Return a sequence of errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a
   map, with :target, :error, :context and all the return value keys
   for the return value of the failed action."
  [results]
  {:pre [(every? #(validate adorned-target-result-map %) results)]
   :post [(every? (fn [r] (validate adorned-target-result-map r)) results)]}
  (seq (remove nil? (map plan-errors results))))

(defn error-exceptions
  "Return a sequence of exceptions from a sequence of results."
  [results]
  {:pre [(every? #(validate adorned-target-result-map %) results)]
   :post [(every? (fn [e] (instance? Throwable e)) %)]}
  (seq (remove nil? (map :exception results))))

(defn throw-errors
  "Throw an exception if there is any exception in results."
  [results]
  {:pre [(every? #(validate adorned-target-result-map %) results)]
   :post [(nil? %)]}
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

(defmacro plan-fn
  "Create a plan function from a sequence of plan function invocations.

   eg. (plan-fn [session]
         (file session \"/some-file\")
         (file session \"/other-file\"))

   This generates a new plan function, and adds code to verify the state
   around each plan function call.

  The plan-fn can be optionally named, as for `fn`."
  [args & body]
  (let [n? (symbol? args)
        n (if n? args)
        args (if n? (first body) args)
        body (if n? (rest body) body)]
    (when-not (vector? args)
      (throw (ex-info
              (str "plan-fn requires a vector for its arguments,"
                   " but none found.")
              {:type :plan-fn-definition-no-args})))
    (when (zero? (count args))
      (throw (ex-info
              (str "plan-fn requires at least one argument for the session,"
                   " but no arguments found.")
              {:type :plan-fn-definition-args-missing})))
    (if n
      `(fn ~n ~args (plan-context ~(gensym (name n)) ~@body))
      `(fn ~args ~@body))))


(defn final-fn-sym?
  "Predicate to match the final function symbol in a form."
  [sym form]
  (loop [form form]
    (when (sequential? form)
      (let [s (first form)]
        (if (and (symbol? s) (= sym (symbol (name s))))
          true
          (recur (last form)))))))

(defmacro defplan
  "Define a plan function. Assumes the first argument is a session map."
  {:arglists '[[name doc-string? attr-map? [params*] body]]
   :indent 'defun}
  [sym & body]
  (letfn [(output-body [[args & body]]
            (let [no-context? (final-fn-sym? sym body)
                  [session-arg session-ref] (map-arg-and-ref (first args))]
              (when-not (vector? args)
                (throw
                 (compiler-exception
                  &form "defplan requires an argument vector" {})))
              (when-not (pos? (count args))
                (throw
                 (compiler-exception
                  &form "defplan requires at least a session argument" {})))
              `([~session-arg ~@(rest args)]
                  ;; if the final function call is recursive, then
                  ;; don't add a plan-context, so that just
                  ;; forwarding different arities only gives one log
                  ;; entry/event, etc.
                  {:pre [(target-session? ~session-arg)]}
                  ~@(if no-context?
                      body
                      [(let [locals (gensym "locals")]
                         `(let [~locals (local-env)]
                            (plan-context '~(symbol (str (name sym)))
                              ~@body)))]))))]
    (let [[sym rest] (name-with-attributes sym body)
          sym (vary-meta sym assoc :pallet/plan-fn true)]
      (if (vector? (first rest))
        `(defn ~sym
           ~@(output-body rest))
        `(defn ~sym
           ~@(map output-body rest))))))

;;; Multi-method for plan functions
(defmacro defmulti-plan
  "Declare a multimethod for plan functions.  Does not have defonce semantics.
  Methods will automatically be wrapped in a plan-context."
  {:arglists '([name docstring? attr-map? dispatch-fn
                {:keys [hierarchy] :as options}])}
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
                {:keys [selector] :as options}])}
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
