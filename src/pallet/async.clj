(ns pallet.async
  "Generally useful async functions"
  (:require
   [clojure.core.async :refer [alts! chan close! go put! thread timeout]]
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
        ;; (errorf e# "Unexpected exception terminating go block")
        (errorf (clojure.stacktrace/root-cause e#)
                "Unexpected exception terminating go block")))))


(defn map-async
  "Apply f to each element of s in a thread per s.

Returns a sequence of channels which can be used to read the results
of each function application.  Each element in the result is a vector,
containing the result of the function call as the first element, and a
map containing any exception thrown, or timeout, as the second
element."

  ;; TODO control the number of threads used here, or under some
  ;; configurable place - might depend on the executor being used
  ;; (eg., a message queue based executor might not need limiting),
  ;; though should probably be configurable separately to this.

  [f s timeout-ms]
  (if false
    ;; this can be used to test code sequentially
    (doall (for [i s]
             (try
               [(f i)]
               (catch Throwable e
                 (println "Caught" e)
                 (errorf e "Unexpected exception")
                 (clojure.stacktrace/print-cause-trace e)
                 [nil e]))))
    (for [i s]
      (timeout-chan
       (thread
        (try
          [(f i)]
          (catch Throwable e [nil {:exception e :target i}])))
       timeout-ms))))
