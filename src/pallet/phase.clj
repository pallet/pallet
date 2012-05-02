(ns pallet.phase
  "A phase is a function of a single `session` argument, that contains
   calls to crate functions or actions. A phase has an implicitly
   defined pre and post phase."
  (:require
   [pallet.context :as context])
  (:use
   [clojure.tools.macro :only [name-with-attributes]]
   [clojure.algo.monads :only [m-result]]
   [pallet.action :only [declare-aggregated-crate-action declare-action]]
   [pallet.common.context :only [throw-map]]
   [pallet.monad :only [phase-pipeline phase-pipeline-no-context
                        session-pipeline local-env]]
   [pallet.session.verify :only [check-session]]
   [pallet.utils :only [compiler-exception]]))

(defn pre-phase-name
  "Return the name for the pre-phase for the given `phase`."
  [phase]
  (keyword "pallet.phase" (str "pre-" (name phase))))

(defn post-phase-name
  "Return the name for the post-phase for the given `phase`."
  [phase]
  (keyword "pallet.phase" (str "post-" (name phase))))

(defn all-phases-for-phase
  "Return a sequence including the implicit pre and post phases for a phase."
  [phase]
  [(pre-phase-name phase) phase (post-phase-name phase)])

(defn subphase-for
  "Return the phase this is a subphase for, or nil if not a subphase"
  [phase]
  (when (= (namespace phase) "pallet.phase")
    (let [n (name phase)
          [_ pre] (re-matches #"pre-(.*)" n)
          [_ post] (re-matches #"post-(.*)" n)
          p (or pre post)]
      (when p
        (keyword p)))))

(defmacro schedule-in-pre-phase
  "Specify that the body should be executed in the pre-phase."
  [& body]
  `(session-pipeline schedule-in-pre-phase {}
     [phase# (get :phase)]
     (assoc :phase (pre-phase-name phase#))
     ~@body
     (assoc :phase phase#)))

(defmacro schedule-in-post-phase
  "Specify that the body should be executed in the post-phase."
  [& body]
  `(session-pipeline schedule-in-post-phase {}
     [phase# (get :phase)]
     (assoc :phase (post-phase-name phase#))
     ~@body
     (assoc :phase phase#)))

(defmacro check-session-thread
  "Add session checking to a sequence of calls which thread a session
   map. e.g.

       (->
         session
         (check-session-thread
           (file \"/some-file\")
           (file \"/other-file\")))

   The example is thus equivalent to:

       (-> session
         (check-session \"The session passed to the pipeline\")
         (check-session (file \"/some-file\"))
         (check-session (file \"/other-file\")))"
  [arg & body]
  `(->
    ~arg
    (check-session "The session passed to the pipeline")
    ~@(mapcat (fn [form] [form `(check-session '~form)]) body)))

(defmacro plan-fn
  "Create a phase function from a sequence of crate invocations with
   an ommited session parameter.

   eg. (plan-fn
         (file \"/some-file\")
         (file \"/other-file\"))

   which generates a function with a session argument, that is thread
   through the function calls. The example is thus equivalent to:

   (fn [session] (-> session
                   (file \"/some-file\")
                   (file \"/other-file\"))) "
  [& body]
  `(session-pipeline ~(gensym "a-plan-fn") {}
     ~@body))


(defmacro defplan
  "Define a crate function."
  {:indent 'defun}
  [sym & body]
  (let [docstring (when (string? (first body)) (first body))
        body (if docstring (rest body) body)
        sym (if docstring (vary-meta sym assoc :doc docstring) sym)]
    `(def ~sym
       (let [locals# (local-env)]
         (phase-pipeline
             ~(symbol (str (name sym) "-cfn"))
             {:msg ~(str sym) :kw ~(keyword sym) :locals locals#}
           ~@body)))))

(defmacro def-plan-fn
  "Define a crate function."
  {:arglists '[[name doc-string? attr-map? [params*] body]]
   :indent 'defun}
  [sym & body]
  (letfn [(output-body [[args & body]]
            (let [p (if (and (sequential? (last body))
                             (symbol? (first (last body)))
                             (= sym (symbol (name (first (last body))))))
                      `phase-pipeline-no-context ; ends in recursive call
                      `phase-pipeline)]
              `(~args
                (let [locals# (local-env)]
                  (~p
                      ~(symbol (str (name sym) "-cfn"))
                      {:msg ~(str sym) :kw ~(keyword sym) :locals locals#}
                    ~@body)))))]
    (let [[sym rest] (name-with-attributes sym body)]
      (if (vector? (first rest))
        `(defn ~sym
           ~@(output-body rest))
        `(defn ~sym
           ~@(map output-body rest))))))

(defmacro def-aggregate-plan-fn
  "Define a crate function where arguments on successive calls are conjoined,
   and passed to the function specified in the body."
  {:arglists '[[name doc-string? attr-map? [params*] f]]
   :indent 'defun}
  [sym & args]
  (let [[sym [args f & rest]] (name-with-attributes sym args)
        id (gensym (name sym))]
    (when (seq rest)
      (throw (compiler-exception
              (IllegalArgumentException.
               (format
                "Extra arguments passed to def-aggregate-crate-fn: %s"
                (vec rest))))))
    `(let [action# (declare-aggregated-crate-action '~sym ~f)]
       (def-plan-fn ~sym
         ;; ~(merge
         ;;   {:execution :aggregated-crate-fn
         ;;    :crate-fn-id (list 'quote id)
         ;;    :action-name (list 'quote sym)}
         ;;   (meta sym))
         [~@args]
         (action# ~@args)))))
