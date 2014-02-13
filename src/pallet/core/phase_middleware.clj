(ns pallet.core.phase-middleware
  "Middleware that is usable at the phase level."
  (:require
   [clojure.core.async :refer [>!! chan]]
   [pallet.async :refer [reduce-results]]
   [pallet.core.api :refer [errors]]))

(defn partition-nodes
  "Return a phase middleware to partition nodes using the specified
  function, partition-f."
  [handler partition-f]
  (fn partition-nodes [session phase targets]
    (let [partitions (partition-by partition-f targets)
          results (loop [partitions partitions
                         result []]
                    (if-let [partition (first partitions)]
                      (let [cs (handler session phase partition)
                            [results exception] (reduce-results cs)
                            result (concat result results)
                            errs (errors results)]
                        (cond
                         exception result
                         errs result
                         :else (recur (rest partitions) result)))
                      result))]
      (loop [results results
             cs []]
        (if-let [result (first results)]
          (let [c (chan 1)]
            (>!! c result)
            (recur (rest results) (conj cs c)))
          cs)))))

(defn post-phase
  "Return a phase middleware that will be invoked on the result of a
  phase lift.  The function is for side effects only.  It is called with
  session, phase, targets, and results."
  [handler post-phase-f]
  (fn post-phase [session phase targets]
    (let [results (handler session phase targets)]
      (post-phase-f session phase targets results)
      results)))
