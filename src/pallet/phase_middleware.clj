(ns pallet.phase-middleware
  "Middleware that is usable at the phase level.
  The middleware should execute asynchronously."
  (:require
   [clojure.core.async :refer [>!! chan]]
   [pallet.plan :refer [errors]]
   [pallet.utils.async :refer [go-try]]))

(defn partition-nodes
  "Return a phase middleware to partition nodes using the specified
  function, partition-f."
  [handler partition-f]
  (fn partition-nodes [session phase targets ch]
    (go-try ch
      (let [partitions (partition-by partition-f targets)
            c (chan)]
        (>! ch (loop [partitions partitions
                      result []]
                 (if-let [partition (first partitions)]
                   (do
                     (handler session phase partition c)
                     (let [[results exception] (<! c)
                           result (concat result results)
                           errs (errors results)]
                       (cond
                        exception [result exception]
                        errs [result]
                        :else (recur (rest partitions) result))))
                   [result])))))))

(defn post-phase
  "Return a phase middleware that will be invoked on the result of a
  phase lift.  The function is for side effects only.  It is called with
  session, phase, targets, and results."
  [handler post-phase-f]
  (fn post-phase [session phase targets ch]
    (go-try ch
      (let [c (chan)]
        (handler session phase targets c)
        (let [results (<! c)]
          (post-phase-f session phase targets results)
          (>! ch results))))))
