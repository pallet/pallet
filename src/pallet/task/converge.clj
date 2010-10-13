(ns pallet.task.converge
  "Adjust node counts."
  (:require
   [pallet.core :as core]
   [clojure.contrib.logging :as logging]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         phases []]
    (if-let [a (first args)]
      (cond
       (and (nil? m) (symbol? a) (nil? (namespace a))) (recur
                                                        (next args)
                                                        (name a)
                                                        m
                                                        phases)
       (not (keyword? a)) (recur
                           (nnext args)
                           prefix
                           (assoc (or m {}) a (fnext args))
                           phases)
       :else (recur (next args) prefix m (conj phases a)))
      (concat [m] (if prefix [:prefix prefix] []) [:phase phases]))))

(defn converge
  "Adjust node counts.  Requires a map of node-type, count pairs.
     eg. pallet converge mynodes/my-node 1
   The node-types should be namespace qualified."
  [request & args]
  (let [args (build-args args)]
    (apply core/converge (concat args :compute (:compute request)))))
