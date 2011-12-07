(ns pallet.monad
  "Pallet monads"
  (:use
   [clojure.algo.monads
    :only [domonad maybe-m monad state-t state-m with-monad defmonadfn m-seq]]
   [clojure.tools.macro :only [symbol-macrolet]]
   [pallet.context :only [with-context]]
   [pallet.phase :only [check-session]]))

;;; monads
(defn state-checking-t
  "Monad transformer that transforms a state monad m into a monad that check its
  state."
  [m checker]
  (monad [m-result (with-monad m
                     m-result)
          m-bind   (with-monad m
                     (fn m-bind-state-checking-t [stm f]
                       (fn state-checking-t-mv [s]
                         (checker s)
                         (let [[_ ss :as r] ((m-bind stm f) s)]
                           (checker ss {:f f :result r})
                           r))))
          m-zero   (with-monad m
                     (if (= ::undefined m-zero)
                       ::undefined
                       (fn [s]
                         m-zero)))
          m-plus   (with-monad m
                     (if (= ::undefined m-plus)
                       ::undefined
                       m-plus))]))

(def
  ^{:doc
    "The pallet session monad. This is fundamentally a state monad, where the
     state is the pallet session map."}
  session-m
  (state-checking-t state-m check-session))

;; (def
;;   ^{:doc "The pallet session sequence monad"}
;;   session-seq-m
;;   (sequence-t state-m))

;;; monadic functions
(defmonadfn m-mapcat
  "'Executes' the sequence of monadic values resulting from mapping
   f onto the values xs. f must return a monadic value."
  [f xs]
  (m-seq (map f xs)))


;;; state accessors
(defn update-in-state
  "Return a state-monad function that replaces the current state by the result
of f applied to the current state and that returns the old state."
  [ks f & args]
  (fn [s] [s (apply update-in s ks f args)]))

(defn assoc-state
  "Return a state-monad function that replaces the current state by the result
of assoc'ing the specified kw-value-pairs onto the current state, and that
returns the old state."
  [& kw-value-pairs]
  (fn assoc-state [s]
    {:pre [(map? s)]}
    [s (apply assoc s kw-value-pairs)]))

(defn dissoc-state
  "Return a state-monad function that removes the specified keys from the
current state, and returns the old state"
  [& keys]
  (fn dissoc-state [s] [s (apply dissoc s keys)]))

(defn get-state
  "Return a state-monad function that gets the specified key from the current
state."
  ([k default]
     (fn get-state [s] [(get s k default) s]))
  ([k]
     (get-state k nil)))

(defn get-in-state
  "Return a state-monad function that gets the specified key from the current
state."
  ([ks default]
     (fn get-in-state [s] [(get-in s ks default) s]))
  ([ks]
     (get-in-state ks nil)))

(defn get-session
  "Return a state-monad function that gets the current sessin."
  []
  (fn get-session [s] [s s]))


;;; comprehensions
(defmacro let-s
  "A monadic comprehension using the session monad."
  [& body]
  `(symbol-macrolet [~'update-in update-in-state
                     ~'assoc assoc-state
                     ~'get get-state
                     ~'get-in get-in-state
                     ~'dissoc dissoc-state]
     (domonad session-m ~@body)))

(defn component-symbol-fn
  "Returns an inline function definition for a component look-up by symbol"
  [k]
  `(fn [session#] ((get (:components session#) '~k ~k) session#)))

;; This is tricky to get right, as it could be many things other than a
;; pipeline call.
;; (defn component-form-fn
;;   "Returns an inline function definition for a component look-up form a form"
;;   [k]
;;   `(fn [& args#]
;;      (fn [session#]
;;        ((apply (get (:components session#) '~(first k) ~(first k)) args#)
;;         session#))))

(defmacro chain-s
  "Defines a monadic comprehension under the session monad, where return value
  bindings can be dropped . Any vector in the arguments is expected to be of the
  form [symbol expr] and becomes part of the generated monad comprehension."
  [& args]
  (letfn [(componentise [s]
            (if (symbol? s)
              (component-symbol-fn s)
              s))
          (gen-step [f]
            (if (vector? f)
              (vec
               (mapcat
                #(vector (first %) (componentise (second %)))
                (partition 2 f)))
              [(gensym "_") (componentise f)]))]
    `(let-s
      [~@(mapcat gen-step args)]
      nil)))

(defmacro ^{:indent 'defun} wrap-pipeline
  "Wraps a pipeline with one or more wrapping forms. Makes the &session symbol
   available withing the bindings"
  [sym & wrappers-and-pipeline]
  `(fn ~sym [~'&session]
     ~(reduce
       #(concat %2 [%1])
       (list (last wrappers-and-pipeline) '&session)
       (reverse (drop-last wrappers-and-pipeline)))))

(defmacro ^{:indent 2} session-pipeline-fn
  "Defines a session pipeline. This composes the body functions under the
  session-m monad. Any vector in the arguments is expected to be of the form
  [symbol expr] and becomes part of the generated monad comprehension."
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(wrap-pipeline ~pipeline-name
       (with-context
           ~(merge {:kw (list 'quote pipeline-name)
                    :msg (name pipeline-name)
                    :ns (list 'quote (ns-name *ns*))
                    :line line}
                   event))
       (chain-s ~@args))))

(defmacro ^{:indent 2} session-pipeline
  "Build and call a session pipeline"
  [name event session & args]
  `(let [name# ~name
         session# ~session]
     (second ((session-pipeline-fn name# ~event ~@args) session#))))


;; (defmacro for-s
;;   "A for comprehension, for use within the session monad."
;;   [& body]
;;   `(monads/domonad session-seq-m ~@body))

;;; helpers
(defn as-session-pipeline-fn
  "Converts a function of session -> session and makes it a monadic value under
  the state monad"
  [f] #(vector nil (f %)))

(defmacro ^{:indent 'defun} session-peek-fn
  "Create a state-m monadic value function that examines the session, and
  returns nil."
  [[sym] & body]
  `(fn [~sym] ~@body [nil ~sym]))
