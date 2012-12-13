(ns pallet.monad
  "# Pallet monads

Pallet uses monads for both internal core code, and for action and phase code
used in crates. The two uses are both fundamentally an application of the state
monad, but are each use a different context."
  (:use
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
   'm-result `m-result
   `update-in `update-in-state
   `assoc `assoc-state
   `assoc-in `assoc-in-state
   `get `get-state
   `get-in `get-in-state
   `dissoc `dissoc-state
   `map `m-map
   `when `m-when
   `when-not `m-when-not})

(defn- replace-in-top-level-form
  "Replace top level monadic function symbols."
  [form]
  (if (and (sequential? form) (not (vector? form)))
    (with-meta
      (list* (get top-level-replacements (first form) (first form)) (rest form))
      (meta form))
    form))

(defn replace-in-top-level-forms
  "Replace some top level function names with their monadic equivalents."
  [forms]
  (vec
   (mapcat
    #(list (first %) (replace-in-top-level-form (second %)))
    (partition 2 forms))))

;; ### Components
;; In order to allow replacement of parts of Pallet's algorithms, each symbol in
;; a pipeline is looked up int the :components key of the session map.
(defn component-symbol-fn
  "Returns an inline function definition for a component look-up by symbol"
  [k]
  `(fn [session#] ((get (:components session#) '~k ~k) session#)))

(defn componentise-top-level-forms
  [forms]
  (letfn [(componentise [s]
            (if (symbol? s)
              (component-symbol-fn s)
              s))]
    (vec
     (mapcat
      #(vector (first %) (componentise (second %)))
      (partition 2 forms)))))

;; ### Action argument delay
(defn wrap-args-if-action
  [form]                                ; no destructuring to preserve metadata
  (let [[fform & args] form]
    (if (and (symbol? fform)
             ;; (when-let [v (resolve fform)]
             ;;   (-> v meta :pallet/action))
             (when-let [v (resolve fform)]
               (not (-> v meta :macro))))
      (with-meta
        (list* fform
               (map
                (fn [arg]
                  (if (or (keyword? arg)(number? arg)(vector? arg)(set? arg))
                    arg
                    `(let [f# (fn ~(gensym "wrap-arg") [] ~arg)]
                       ;; written like this to avoid recur across catch errors
                       (try+
                        (f#)
                        (catch [:type :pallet/access-of-unset-node-value] _#
                          (delayed [~'&session] (f#)))))))
                args))
        (meta form))
      form)))

(defn wrap-action-args
  "Replace arguments to actions."
  [forms]
  (vec
   (mapcat
    #(list (first %) (wrap-args-if-action (second %)))
    (partition 2 forms))))



;; ### Let Comprehension
(defmacro let-state
  "A monadic comprehension using the state-m monad. Adds lookup of components."
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
  [& body]
  `(dostate
    ~(->
      (first body)
      replace-in-top-level-forms
      componentise-top-level-forms
      wrap-action-args)
    ~@(rest body)))

;; ### Pipelines
(defmacro chain-s
  "Defines a monadic comprehension under the session monad, where return value
  bindings not specified. Any vector in the arguments is expected to be of the
  form [symbol expr] and becomes part of the generated monad comprehension."
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
  {:indent 'defun}
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
  {:indent 2}
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
  {:indent 2}
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
  {:indent 2}
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
  {:indent 2}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~pipeline-name
       (chain-s ~@args))))

;;; ## Helpers
(defn exec-s
  "Call a session-m state monad function, returning only the session map."
  [pipeline session]
  (second (pipeline session)))

(defn as-session-pipeline-fn
  "Converts a function of session -> session and makes it a monadic value under
  the state monad"
  [f] #(vector nil (f %)))

(defmacro local-env
  "Return the local environment as a map of keyword value pairs."
  []
  (letfn [(not-gensym? [sym] #(not (.contains (name sym) "__")))]
    (into {}
          (map
           #(vector (keyword (name %)) %)
           (filter not-gensym? (keys &env))))))
