(ns pallet.plan
  "API for execution of pallet plan functions.

TODO: consider renaming this to pallet.core.plan, and reserving the
api namespace for exposing functions from other namespaces, adding
functions with more defaults, etc."
  (:require
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.core.type-annotations]
   [pallet.core.types                   ; before any protocols
    :refer [assert-not-nil assert-type-predicate keyword-map?
            Action ActionErrorMap ActionResult BaseSession EnvironmentMap
            ErrorMap ExecSettings ExecSettingsFn Keyword Phase PlanResult
            PlanFn PlanState Result Session User]]
   [clojure.string :as string]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.context :refer [with-phase-context]]
   [pallet.core.executor :as executor]
   [pallet.core.node-os :refer :all]
   [pallet.core.plan-state :refer [get-scopes]]
   [pallet.core.recorder :refer [record results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.recorder.juxt :refer [juxt-recorder]]
   [pallet.node :refer [node?]]
   [pallet.session
    :refer [base-session?
            executor plan-state recorder set-target
            set-executor set-recorder target-session? user]]
   [pallet.user :refer [obfuscated-passwords user?]]
   [pallet.utils
    :refer [apply-map compiler-exception local-env map-arg-and-ref]])
  (:import
   clojure.lang.IMapEntry
   pallet.compute.protocols.Node))

;;; ## Action Plan Execution

;;; # Execute action on a target
(ann execute-action [Session Action -> ActionResult])
;; TODO - remove tc-gnore when update-in has more smarts
(defn execute-action
  "Execute an action map within the context of the current session."
  [{:keys [target] :as session} action]
  {:pre [(target-session? session)
         (user? (user session))
         (map? target)]}
  (debugf "execute-action action %s" (pr-str action))
  (tracef "execute-action session %s" (pr-str session))
  (let [executor (executor session)]
    (tracef "execute-action executor %s" (pr-str executor))
    (assert executor "No executor in session")
    (let [[rv e] (try
                   [(executor/execute executor target (user session) action)]
                   (catch Exception e
                     (let [rv (:result (ex-data e))]
                       (when-not rv
                         ;; Exception isn't of the expected form, so
                         ;; just re-throw it.
                         (throw e))
                       [rv e])))]
      (tracef "execute-action rv %s" (pr-str rv))
      (assert (map? rv) (str "Action return value must be a map: " (pr-str rv)))
      (record (recorder session) rv)
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
         (or (nil? (:node target)) (node? (:node target)))
         ;; Reduce preconditions? or reduce magic by not having defaults?
         ;; TODO have a default executor?
         (executor session)]}
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

        (let [rv (try
                   (plan-fn session)
                   (catch Exception e
                     ;; Wrap the exception so we can return the
                     ;; accumulated results.
                     (throw (ex-info "Exception in plan-fn"
                                     {:action-results (results r)
                                      :target target}
                                     e))))]
          {:action-results (results r)
           :return-value rv
           :target target})))))

(defn execute
  "Execute a plan function.  If there is a `target` with a `:node`,
  then we execute the plan-fn using the core execute function,
  otherwise we just call the plan function."
  [session {:keys [node] :as target} plan-fn]
  {:pre [(map? target)]}
  (debugf "execute %s %s" target plan-fn)
  (if node
    (execute* session target plan-fn)
    (if plan-fn
      {:return-value (plan-fn session)})))


;;; TODO make sure these error reporting functions are relevant and correct

;;; # Exception reporting
;; TODO remove no-check when IllegalArgumentException not thrown by core.typed
(ann ^:no-check errors
     [(Nilable (NonEmptySeqable PlanResult)) ->
      (Nilable (NonEmptySeqable PlanResult))])
(defn errors
  "Return a sequence of errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a
   map, with :target, :error, :context and all the return value keys
   for the return value of the failed action."
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




;;; ### plan functions

;;; The phase context is used in actions and crate functions. The
;;; phase context automatically sets up a context, which is available
;;; (for logging, etc) at phase execution time.
(defmacro plan-context
  "Defines a block with a context that is automatically added."
  {:indent 2}
  [context-name event & args]
  (let [line (-> &form meta :line)]
    `(with-phase-context
       (merge {:kw ~(list 'quote context-name)
               :msg ~(if (symbol? context-name)
                       (name context-name)
                       context-name)
               :ns ~(list 'quote (ns-name *ns*))
               :line ~line
               :log-level :trace}
              ~event)
       ~@args)))


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
    (if n
      `(fn ~n ~args (plan-context ~(gensym (name n)) {} ~@body))
      `(fn ~args (plan-context ~(gensym "a-plan-fn") {} ~@body)))))


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
  "Define a plan function."
  {:arglists '[[name doc-string? attr-map? [params*] body]]
   :indent 'defun}
  [sym & body]
  (letfn [(output-body [[args & body]]
            (let [no-context? (final-fn-sym? sym body)
                  [session-arg session-ref] (map-arg-and-ref (first args))]
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
  "Declare a multimethod for plan functions"
  {:arglists '([name docstring? attr-map? dispatch-fn
                & {:keys [hierarchy] :as options}])}
  [name & args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        [attr-map args] (if (map? (first args))
                          [(first args) (rest args)]
                          [nil args])
        dispatch-fn (first args)
        {:keys [hierarchy]
         :or {hierarchy #'clojure.core/global-hierarchy}} (rest args)
        args (first (filter vector? dispatch-fn))
        name (vary-meta name assoc :pallet/plan-fn true)]
    `(let [a# (atom {})]
       (def
         ~name
         ^{:dispatch-fn (fn [~@args] ~@(rest dispatch-fn))
           :methods a#}
         (fn [~@args]
           (let [dispatch-val# ((-> ~name meta :dispatch-fn) ~@args)]
             (if-let [f# (or (get @a# dispatch-val#)
                             (some
                              (fn [[k# f#]]
                                (when (isa? @~hierarchy dispatch-val# k#)
                                  f#))
                              @a#))]
               (f# ~@args)
               (throw
                (ex-info
                 (format "Missing plan-multi %s dispatch for %s"
                         ~(clojure.core/name name) (pr-str dispatch-val#))
                 {:reason :missing-method
                  :plan-multi ~(clojure.core/name name)})))))))))

(defn
  ^{:internal true :indent 2}
  add-plan-method-to-multi
  [multifn dispatch-val f]
  (swap! (-> multifn meta :methods) assoc dispatch-val f))

(defmacro defmethod-plan
  {:indent 2}
  [multifn dispatch-val args & body]
  (letfn [(sanitise [v]
            (string/replace (str v) #":" ""))]
    `(add-plan-method-to-multi ~multifn ~dispatch-val
       (fn [~@args]
         (plan-context
             ~(symbol (str (name multifn) "-" (sanitise dispatch-val)))
             {:msg ~(name multifn) :kw ~(keyword (name multifn))
              :dispatch-val ~dispatch-val}
           ~@body)))))


;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (plan-fn 1))
;; End:
