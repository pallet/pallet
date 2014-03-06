(ns pallet.plan
  "API for execution of pallet plan functions."
  (:require
   [clojure.core.async :as async :refer [<!! chan]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :refer [debugf]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.core.type-annotations]
   [pallet.core.types                   ; before any protocols
    :refer [assert-not-nil assert-type-predicate keyword-map?
            Action ActionErrorMap ActionResult BaseSession EnvironmentMap
            ErrorMap ExecSettings ExecSettingsFn Keyword Phase PlanResult
            PlanFn PlanState Result Session TargetMap User]]
   [clojure.string :as string]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.context :refer [with-phase-context]]
   [pallet.core.executor :as executor]
   [pallet.core.node-os :refer [with-script-for-node]]
   [pallet.core.plan-state :refer [get-scopes]]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.exception
    :refer [combine-exceptions compiler-exception domain-error?]]
   [pallet.session
    :refer [base-session?
            executor plan-state recorder
            set-executor set-recorder target-session? user]]
   [pallet.target :refer [has-node? set-target]]
   [pallet.user :refer [obfuscated-passwords user?]]
   [pallet.utils :refer [local-env map-arg-and-ref]]
   [pallet.utils.async :refer [concat-chans go-try map-thread reduce-results]]
   [pallet.utils.multi :as multi
    :refer [add-method dispatch-every-fn dispatch-key-fn dispatch-map]]
   [schema.core :as schema :refer [check required-key optional-key validate]])
  (:import
   clojure.lang.IMapEntry
   pallet.compute.protocols.Node))

;;; # Action Plan Execution

(def action-result-map
  {schema/Keyword schema/Any})

(def plan-result-map
  {:action-results [action-result-map]
   (optional-key :return-value) schema/Any
   (optional-key :exception) Exception})

(def plan-exception-map
  (assoc plan-result-map :target schema/Any))

;;; ## Execute action on a target
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
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
        (record (recorder session) rv))
      (when e
        (throw e))
      rv)))

(ann execute* [BaseSession TargetMap PlanFn -> PlanResult])
(defn execute*
  "Execute a plan function on a target.

Ensures that the session target is set, and that the script
environment is set up for the target.

Returns a map with `:target`, `:return-value` and `:action-results`
keys.

The result is also written to the recorder in the session."
  [session target plan-fn]
  {:pre [(base-session? session)
         (map? target)
         (or (nil? plan-fn) (fn? plan-fn))
         (or (nil? (:node target)) (has-node? target))
         ;; Reduce preconditions? or reduce magic by not having defaults?
         ;; TODO have a default executor?
         (executor session)]
   :post [(or (nil? %) (validate plan-result-map %))]}
  (debugf "execute* %s %s" target plan-fn)
  (when plan-fn
    (let [r (in-memory-recorder) ; a recorder for the scope of this plan-fn
          session (-> session
                      (set-target target)
                      (set-recorder (if-let [recorder (recorder session)]
                                      (juxt-recorder [r recorder])
                                      r)))]
      (assert (target-session? session) "target session created correctly")
      (with-script-for-node target (plan-state session)
        ;; We need the script context for script blocks in the plan
        ;; functions.
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

(ann execute [BaseSession TargetMap PlanFn -> PlanResult])
(defn execute
  "Execute a plan function.  If there is a `target` with a `:node`,
  then we execute the plan-fn using the core execute function,
  otherwise we just call the plan function.
  Suitable for use as a plan middleware handler function."
  [session {:keys [node] :as target} plan-fn]
  {:pre [(map? target)]}
  (debugf "execute %s %s" target plan-fn)
  (if node
    (execute* session target plan-fn)
    (if plan-fn
      {:return-value (plan-fn session)})))

(def target-result-map
  (-> plan-result-map
      (dissoc :action-results)
      (assoc (optional-key :action-results) [action-result-map]
             :target {schema/Keyword schema/Any})))

(defn execute-target-plan
  "Using the session, execute plan-fn on target. Uses any plan
  middleware defined on the plan-fn."
  [session target plan-fn]
  {:pre [(or (nil? plan-fn) (fn? plan-fn))]
   :post [(validate target-result-map %)]}
  (let [{:keys [middleware]} (meta plan-fn)]
    (-> (if middleware
          (middleware session target plan-fn)
          (execute session target plan-fn))
        (assoc :target target))))

;;; # Execute Plan Functions on Mulitple Targets
(defn ^:internal execute-plan-fns*
  "Using the executor in `session`, execute phase on all targets.
  The targets are executed in parallel, each in its own thread.  A
  single [result, exception] tuple will be written to the channel ch,
  where result is a sequence of results for each target, and exception
  is a composite exception of any thrown exceptions.

  Does not call phase middleware.  Does call plan middleware."
  [session target-plans ch]
  (let [c (chan)]
    (->
     (map-thread (fn execute-plan-fns [[target plan-fn]]
                   (try
                     [(execute-target-plan session target plan-fn)]
                     (catch Exception e
                       (let [data (ex-data e)]
                         (if (contains? data :action-results)
                           [data e]
                           [nil e])))))
                 target-plans)
     (concat-chans c))
    (reduce-results c ch)))

(defn execute-plan-fns
  "Execute plan functions on targets.  Write a result tuple to the
  channel, ch.  Targets are grouped by phase-middleware, and phase
  middleware is called.  plans are executed in parallel.
  `target-plans` is a sequence of target, plan-fn tuples."
  ([session target-plans ch]
     (debugf "execute-plan-fns %s target-plans" (count target-plans))
     (let [mw-targets (group-by (comp :phase-middleware meta second)
                                target-plans)
           c (chan)]
       (concat-chans
        (for [[mw target-plans] mw-targets]
          (if mw
            (mw session target-plans ch)
            (execute-plan-fns* session target-plans ch)))
        c)
       (reduce-results c ch)))
  ([session target-plans]
     (let [c (chan)]
       (execute-plan-fns session target-plans c)
       (let [[results e] (<!! c)]
         (if (or (nil? e) (domain-error? e))
           results
           (throw (ex-info "execute-plan-fns failed" {:results results} e)))))))

;;; TODO make sure these error reporting functions are relevant and correct

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

;;; ### plan functions

;;; The phase context is used in actions and crate functions. The
;;; phase context automatically sets up a context, which is available
;;; (for logging, etc) at phase execution time.
(defmacro plan-context
  "Defines a block with a context that is automatically added."
  {:indent 2}
  [context-name event & args]
  (let [line (-> &form meta :line)]
    `(let [event# ~event]
       (assert (or (nil? event#) (map? event#))
               "plan-context second arg must be a map")
       (with-phase-context
           (merge {:kw ~(list 'quote context-name)
                   :msg ~(if (symbol? context-name)
                           (name context-name)
                           context-name)
                   :ns ~(list 'quote (ns-name *ns*))
                   :line ~line
                   :log-level :trace}
                  event#)
           ~@args))))


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
      `(fn ~n ~args (plan-context ~(gensym (name n)) {} ~@body))
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
                            (plan-context
                                ~(symbol (str (name sym) "-cfn"))
                                {:msg ~(str sym)
                                 :kw ~(keyword sym)
                                 :locals ~locals}
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
                ~(symbol (str (name multifn) "-" (sanitise dispatch-val)))
                {:msg ~(name multifn) :kw ~(keyword (name multifn))
                 :dispatch-val ~dispatch-val}
              ~@body))
         (meta &form)))))
