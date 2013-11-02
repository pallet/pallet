(ns pallet.async
  "Generally useful async functions"
  (:require
   [clojure.core.async :refer [alts! chan close! go put! timeout]]))

;;; # Async
;; (defn timeout-chan
;;   "Returns a channel that returns a value from the input channel,
;;   or timeout-value, if the input channel ch does not supply a value
;;   before timeout-ms passes."
;;   [ch timeout-ms timeout-value]
;;   (go
;;    (let [[v c] (alts! [ch (timeout timeout-ms)])]
;;      (or v timeout-value))))


(defn timeout-chan
  "Returns a channel that will receive values from ch until ch is closed, or
  the specified timeout period expires."
  [ch timeout-ms]
  (let [out-ch (chan)
        timeout-ch (timeout timeout-ms)]
    (go
     (loop [[v c] (alts! [ch timeout-ch])]
       (when v
         (put! out-ch v)
         (recur (alts! [ch timeout-ch]))))
     (close! out-ch))
    out-ch))
