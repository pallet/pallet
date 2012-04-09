(ns pallet.operations
  "The pallet operations DSL"
  (:use
   [clojure.set :only [union]]
   [clojure.algo.monads :only [state-m domonad]]
   [pallet.operate :only [implement-operation]]))

(def operation-m state-m)

(defmacro let-ops
  "A monadic comprehension using the operation-m monad."
  [& body]
  `(domonad operation-m
     ~@body))

(defmacro chain-ops
  "Defines a monadic comprehension under the operation-m monad, where return
  value bindings not specified. Any vector in the arguments is expected to be of
  the form [symbol expr] and becomes part of the generated monad comprehension."
  [& args]
  (letfn [(gen-step [f]
            (if (vector? f)
              f
              [(gensym "_") f]))
          (translate-step [[sym f]]
            [sym `(implement-operation ~f)])]
    (let [bindings (->> args (map gen-step) (mapcat translate-step))]
      `(let-ops
         [~@bindings]
         ~(last (drop-last bindings))))))

(defmacro operations
  "Define a sequence of operations."
  [& body]
  `(into {} (map (juxt :op-name identity) [~@body])))

;; (fn ~op-name [~@args]
;;            ((chain-ops ~@steps)
;;             (zipmap ~quoted-args [~@args])))

;; should the operation be a defn-like macro that specifies arguments
;; or just a data map? should there be a def-operation ?
(defmacro operation
  "Define an operation. Arguments `args` are keywords."
  {:indent 2}
  [op-name [& args] & steps]
  (letfn [(quote-if-symbol [s] (if (symbol s) (list 'quote s) s))
          (gen-step [f]
            (if (vector? f)
              f
              [(gensym "_") f]))]
    (let [quoted-args (vec (map #(list 'quote %) args))
          steps (->> steps
                     (mapcat gen-step)
                     (partition 2)
                     (map #(vector
                            (list 'quote (first %))
                            (map quote-if-symbol (second %))))
                     (map #(hash-map
                            :result-sym (first %)
                            :op-sym (-> % second first)
                            :args (-> % second rest vec)))
                     vec)]
      `{:op-name '~op-name
        :args ~quoted-args
        :steps ~steps})))
