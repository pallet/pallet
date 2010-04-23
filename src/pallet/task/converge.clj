(ns pallet.task.converge
  "Adjust node counts."
  (:require [pallet.core :as core]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         phases []]
    (if-let [a (first args)]
      (cond
       (and (nil? m) (string? a)) (recur (next args) a m phases)
       (not (keyword? a)) (recur
                           (nnext args) prefix (assoc (or m {}) a (fnext args)) phases)
       :else (recur (next args) prefix m (conj phases a)))
      (let [res (if prefix [prefix] [])]
        (concat (conj res m) phases)))))

(defn converge
  "Adjust node counts.  Requires a map of node-type, count pairs.
     eg. pallet converge mynodes/my-node 1
   The node-types should be namespace qualified."
  [& args]
  (let [args (build-args args)]
    (apply core/converge args)))
