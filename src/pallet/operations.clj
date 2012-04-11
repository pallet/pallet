(ns pallet.operations
  "The pallet operations DSL"
  (:use
   [clojure.set :only [union]]
   [clojure.algo.monads :only [state-m domonad]]
   [clojure.walk :only [postwalk]]))

(def operation-m state-m)

(defmacro let-ops
  "A monadic comprehension using the operation-m monad."
  [& body]
  `(domonad operation-m
     ~@body))

;; (defmacro chain-ops
;;   "Defines a monadic comprehension under the operation-m monad, where return
;;   value bindings not specified. Any vector in the arguments is expected to be of
;;   the form [symbol expr] and becomes part of the generated monad comprehension."
;;   [& args]
;;   (letfn [(gen-step [f]
;;             (if (vector? f)
;;               f
;;               [(gensym "_") f]))
;;           (translate-step [[sym f]]
;;             [sym `(implement-operation ~f)])]
;;     (let [bindings (->> args (map gen-step) (mapcat translate-step))]
;;       `(let-ops
;;          [~@bindings]
;;          ~(last (drop-last bindings))))))

(defmacro operations
  "Define a sequence of operations."
  [& body]
  `(into {} (map (juxt :op-name identity) [~@body])))

;; (fn ~op-name [~@args]
;;            ((chain-ops ~@steps)
;;             (zipmap ~quoted-args [~@args])))

(defn replace-syms
  "Recursively transforms form by replacing symbols with a look-up in an 'env
  map, returning the unquoted symbol if the symbol isn't in the map."
  [map-sym form]
  (postwalk (fn [x]
              (if (symbol? x)
                `(let [v# (~map-sym '~x ::nf)]
                   (if (= v# ::nf)
                     (if-let [w# (ns-resolve '~(ns-name *ns*) '`~~x)]
                       w#
                       (throw
                        (Exception.
                         (str "failed to resolve " '`~~x
                              " in " '~(ns-name *ns*)))))
                     v#))
                x)) form))

(defn eval-in-env-fn
  "Give a form, return a function of a single `env` argument and that evaluates
the form in the given environment."
  [form]
  `(fn [~'env]
     ~(replace-syms 'env form)))

(defmacro locals-map
  []
  (zipmap (map #(list 'quote %) (keys &env)) (keys &env)))

(defn set-in-env-fn
  "Give an expression that is a valid lhs in a binding, return a function of an
  `env` argument and a value that assigns the results of destructuring into
  `env`."
  [expr]
  (let [env (gensym "env") result (gensym "result")]
    `(fn [~env ~result]
       (let [~expr ~result
             locals# (locals-map)]
         (merge
          ~env
          (apply
           dissoc locals# '~env '~result
           (->> (keys locals#)
                (remove #(not (re-matches #".*__[0-9]+" (name %)))))))))))

;; should the operation be a defn-like macro that specifies arguments
;; or just a data map? should there be a def-operation ?
(defmacro operation
  "Define an operation. Arguments `args` are keywords."
  {:indent 2}
  [op-name [& args] steps result]
  (letfn [(quote-if-symbol [s] (if (symbol? s) (list 'quote s) s))
          ;; (gen-step [f]
          ;;   (if (vector? f)
          ;;     f
          ;;     [(gensym "_") f]))
          ]
    (let [quoted-args (vec (map #(list 'quote %) args))
          steps (->> steps
                     ;; (mapcat gen-step)
                     (partition 2)
                     (map #(hash-map
                            :result-sym (list 'quote (first %))
                            :result-f (set-in-env-fn (first %))
                            :op-sym (list 'quote (second %))
                            :f (eval-in-env-fn (second %))))
                     vec)]
      `{:op-name '~op-name
        :args ~quoted-args
        :steps ~steps
        :result-sym '~result})))
