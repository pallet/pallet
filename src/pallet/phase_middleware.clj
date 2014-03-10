(ns pallet.phase-middleware
  "Middleware that is usable at the phase level.
  The middleware must execute asynchronously, returning the
  channel for it's async go block.  It must write a result to
  it's channel argument."
  (:require
   [clojure.core.async :refer [>!! chan]]
   [pallet.plan :refer [errors]]
   [pallet.utils.async :refer [go-try]]))

(defn partition-target-plans
  "Return a phase middleware to partition target-plans using the
  specified function, partition-f.  The partition-f function takes a
  sequence of target, plan-fn tuples and returns a sequence of target,
  plan-fn tuple sequences."
  [handler partition-f]
  (fn partition-nodes [session target-plans ch]
    (go-try ch
      (let [partitions (partition-by partition-f target-plans)
            c (chan)]
        (>! ch
            (loop [partitions partitions
                   result []]
              (if-let [partition (first partitions)]
                (do
                  (handler session partition c)
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
  session, targets, and results."
  [handler post-phase-f]
  (fn post-phase [session target-plans ch]
    (go-try ch
      (let [c (chan)]
        (handler session target-plans c)
        (let [results (<! c)]
          (post-phase-f session (map first target-plans) results)
          (>! ch results))))))
