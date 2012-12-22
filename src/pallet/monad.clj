(ns pallet.monad
  "# Pallet monads

Pallet uses monads for both internal core code, and for action and phase code
used in crates. The two uses are both fundamentally an application of the state
monad, but are each use a different context."
  (:use
   [clojure.tools.logging :only [debugf tracef]]
   [pallet.monad.state-monad
    :only [check-state-with dostate m-when m-when-not m-map m-result]]
   [pallet.argument :only [delayed]]
   [pallet.context :only [with-context with-phase-context]]
   pallet.monad.state-accessors
   [pallet.session.verify :only [check-session]]
   [slingshot.slingshot :only [try+]]))

;;; ## Pallet Monads and Monad Transformers

;;; The basic pallet monad is a state monad, where the state is pallet's session
;;; map. The state monad checks the validity of the session map, before and
;;; after each monadic call.

(check-state-with check-session)

;;; ## Comprehensions

;;; ### Helpers
(defn list-form?
  [form]
  (and (sequential? form) (not (vector? form))))


;; ### Rewriting of core symbols to monadic equivalents.

;; This is alpha at present. It could give rise to unexpected behaviour. The aim
;; is to make the monadic code look more like plain clojure code, and simplify
;; the number of new functions that must be learned.
(def ^{:doc
       "A map of symbols that will be translated when they appear as the
        monadic function in a `let-s` monadic comprehension."
       :private true}
  top-level-replacements
  {'update-in `update-in-state
   'assoc `assoc-state
   'assoc-in `assoc-in-state
   'get `get-state
   'get-in `get-in-state
   'dissoc `dissoc-state
   'map `m-map
   'when `m-when
   'when-not `m-when-not
   `update-in `update-in-state
   `assoc `assoc-state
   `assoc-in `assoc-in-state
   `get `get-state
   `get-in `get-in-state
   `dissoc `dissoc-state
   `map `m-map
   `when `m-when
   `when-not `m-when-not})

(defn replace-in-top-level-form*
  "Replace top level monadic function symbols."
  [form]
  (if (and (sequential? form) (not (vector? form)))
    (do
      (when-not (= (first form)
                   (get top-level-replacements (first form) (first form)))
        (println "*** replacing "
                 form
                 "using"
                 (get top-level-replacements (first form) (first form))
                 "at"
                 *file*
                 (:line (meta form))))
      (with-meta
        (list* (get top-level-replacements (first form) (first form)) (rest form))
        (meta form)))
    form))

(defmacro replace-in-top-level-form
  [form]
  (replace-in-top-level-form* form))

(defn replace-in-top-level-forms
  "Replace some top level function names with their monadic equivalents."
  [forms]
  (vec
   (mapcat
    #(list (first %) (replace-in-top-level-form* (second %)))
    (partition 2 forms))))

;; ### Components
;; In order to allow replacement of parts of Pallet's algorithms, each symbol in
;; a pipeline is looked up int the :components key of the session map.
(defn component-symbol-fn
  "Returns an inline function definition for a component look-up by symbol"
  [k]
  (if (symbol? k)
    `(fn [session#] ((get (:components session#) '~k ~k) session#))
    k))

(defn componentise-top-level-forms
  [forms]
  (vec
   (mapcat
    #(vector (first %) (component-symbol-fn (second %)))
    (partition 2 forms))))


;; ### Action argument delay
(defn wrap-args-if-action*
  [form]                                ; no destructuring to preserve metadata
  (if (list-form? form)
    (let [[fform & args] form]
      (if (and
           (symbol? fform)
           ;; (when-let [v (resolve fform)]
           ;;   (-> v meta :pallet/action))
           (when-let [v (resolve fform)]
             (not (-> v meta :macro))))
        (with-meta
          (list* fform
                 (map
                  (fn [arg]
                    (if (or (keyword? arg)
                            (number? arg)
                            (vector? arg)
                            (set? arg)
                            (string? arg))
                      arg
                      `(let [f# (fn ~(gensym "wrap-arg") [] ~arg)]
                         ;; written like this to avoid recur across catch errors
                         (try+
                          (f#)
                          (catch [:type :pallet/access-of-unset-node-value] _#
                            (delayed [~'&session] (f#)))))))
                  args))
          (meta form))
        form))
    form))

(defmacro wrap-args-if-action
  [form]
  (wrap-args-if-action* form))

(defn wrap-action-args
  "Replace arguments to actions."
  [forms]
  (vec
   (mapcat
    #(list (first %) (wrap-args-if-action* (second %)))
    (partition 2 forms))))

;;; ### Monadisation
;;; Check for forms that require m-result wrapping
(defn- contains-plan-fn?
  "Predicate to see if a plan-fn contains a symbol that resolves
   to a var that has :pallet/plan-fn metadata"
  [env form]
  (cond
   (symbol? form) (or (#{'m-result 'm-identity} form)
                      (when-let [v (and (not (get env form)) (resolve form))]
                        (println
                         (format "sym %s %s %s"
                                         form v (:pallet/plan-fn (meta v))))
                        (:pallet/plan-fn (meta v))))
   (list-form? form) (some (partial contains-plan-fn? env) form)
   :else nil)) ;(println "ignore form" form (type form))

(defmacro m-identity
  "Wrapper to prevent form translation to m-result."
  {:pallet/plan-fn true}
  [x]
  x)

(defn translate-form*
  "Translate a pallet plan-fn form.  This looks to see if the form contains
   a symbol with :pallet/plan-fn metadata, and if not wraps it in a m-result.
   It could also translate outer map, when, etc into m-map, m-when, etc if
   a plan-fn is found."
  [env form]
  (if (or (symbol? form) (list-form? form))
    (->
     (if (contains-plan-fn? env form)
       (do
         (println
          "*** monadic fn"
          (component-symbol-fn (replace-in-top-level-form form))
          "at" *file* (:line (meta form)))
         (component-symbol-fn (replace-in-top-level-form form)))
       (do
         (println
          "*** translating to (m-result" form ") at" *file* (:line (meta form)))
         (list `m-result form)))
     (with-meta (meta form)))
    form))

(defmacro translate-form
  [form]
  (translate-form* &env form))

(defn translate-forms
  "Translate pallet plan-fn forms."
  [env forms]
  (vec
   (mapcat
    #(list (first %) (with-meta
                       `(translate-form
                         ~(with-meta
                            `(wrap-args-if-action ~(second %))
                            (meta (second %))))
                       (meta (second %))))
    (partition 2 forms))))



;; ### Let Comprehension
(defmacro let-state
  "A monadic comprehension using the state-m monad. Adds lookup of components."
  {:pallet/plan-fn true}
  [& body]
  `(dostate
    ~(->
      (first body)
      componentise-top-level-forms)
    ~@(rest body)))

(defmacro let-s
  "A monadic comprehension using the session-m monad. Provides some translation
   of functions used (see `top-level-replacements`), and adds lookup of
   components."
  {:pallet/plan-fn true}
  [& body]
  `(dostate
    ~(->>
      (first body)
      (translate-forms &env)
      ;; wrap-action-args
      )
    ~@(rest body)))

;; ### Pipelines
(defmacro chain-s
  "Defines a monadic comprehension under the session monad, where return value
  bindings not specified. Any vector in the arguments is expected to be of the
  form [symbol expr] and becomes part of the generated monad comprehension."
  {:pallet/plan-fn true}
  [& args]
  (letfn [(gen-step [f]
            (if (vector? f)
              f
              [(gensym "_") f]))]
    (let [bindings (mapcat gen-step args)]
      `(let-s
        [~@bindings]
        ~(last (drop-last bindings))))))

(defmacro wrap-pipeline
  "Wraps a pipeline with one or more wrapping forms. Makes the &session symbol
   available withing the bindings."
  {:indent 'defun
   :pallet/plan-fn true}
  [sym & wrappers-and-pipeline]
  `(fn ~sym [~'&session]
     ~(reduce
       #(concat %2 [%1])
       (list (last wrappers-and-pipeline) '&session)
       (reverse (drop-last wrappers-and-pipeline)))))

;;; ## Session Pipeline
;;; The session pipeline is used in pallet core code.
(defmacro session-pipeline
  "Defines a session pipeline. This composes the body functions under the
  session-m monad. Any vector in the arguments is expected to be of the form
  [symbol expr] and becomes part of the generated monad comprehension."
  {:indent 2
   :pallet/plan-fn true}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~pipeline-name
       (with-context
           ~(merge {:kw (list 'quote pipeline-name)
                    :msg (name pipeline-name)
                    :ns (list 'quote (ns-name *ns*))
                    :line line
                    :log-level :debug}
                   event))
       (chain-s ~@args))))

(defmacro let-session
  "Defines a session comprehension."
  {:indent 2
   :pallet/plan-fn true}
  [comprehension-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~comprehension-name
       (with-context
           ~(merge {:kw (list 'quote comprehension-name)
                    :msg (name comprehension-name)
                    :ns (list 'quote (ns-name *ns*))
                    :line line
                    :log-level :debug}
                   event))
       (let-s ~@args))))

;;; ## Phase Pipeline

;;; The phase pipeline is used in actions and crate functions. The phase
;;; pipeline automatically sets up the phase context, which is available
;;; (for logging, etc) at phase execution time.
(defmacro phase-pipeline
  "Defines a session pipeline. This composes the body functions under the
  session-m monad. Any vector in the arguments is expected to be of the form
  [symbol expr] and becomes part of the generated monad comprehension.

  The resulting function is wrapped in a named anonymous function to improve
  stack traces.

  A context is automatically added."
  {:indent 2
   :pallet/plan-fn true}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~pipeline-name
       (with-phase-context
           (merge {:kw ~(list 'quote pipeline-name)
                   :msg ~(name pipeline-name)
                   :ns ~(list 'quote (ns-name *ns*))
                   :line ~line
                   :log-level :debug}
                  ~event))
       (chain-s ~@args))))

(defmacro phase-pipeline-no-context
  "Similar to `phase-pipeline`, but without the context."
  {:indent 2
   :pallet/plan-fn true}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~pipeline-name
       (chain-s ~@args))))

;;; ## Helpers
(defn exec-s
  "Call a session-m state monad function, returning only the session map."
  {:pallet/plan-fn true}
  [pipeline session]
  (second (pipeline session)))

(defn as-plan-fn
  "Converts a function of no arguments into a plan-fn"
  {:pallet/plan-fn true}
  [f] (fn as-plan-fn [session] [(f) session]))

(defmacro local-env
  "Return the local environment as a map of keyword value pairs."
  []
  (letfn [(not-gensym? [sym] #(not (.contains (name sym) "__")))]
    (into {}
          (map
           #(vector (keyword (name %)) %)
           (filter not-gensym? (keys &env))))))
