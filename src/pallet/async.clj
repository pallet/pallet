(ns pallet.async
  "Generally useful async functions"
  (:require
   [clojure.core.async :refer [alts! chan close! go put! timeout]]
   [clojure.tools.logging :refer [errorf]]))

;;; # Async

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

(defmacro go-logged
  [& body]
  `(go
    (try
      ~@body
      (catch Throwable e#
        (errorf e# "Unexpected exception terminating go block")))))
