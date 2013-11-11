(ns pallet.async
  "Generally useful async functions"
  (:require
   [clojure.core.typed :refer [ann for> AnyInteger Nilable Seqable]]
   [clojure.core.typed.async :refer [go> ReadOnlyPort]]
   [clojure.core.async :refer [alts! chan close! go put! thread timeout]]
   [clojure.tools.logging :refer [errorf]]
   [pallet.core.types :refer [ErrorMap]]))

;;; # Async
(ann timeout-chan (All [x]
                    [(ReadOnlyPort x) AnyInteger -> (ReadOnlyPort x)]))
(defn timeout-chan
  "Returns a channel that will receive values from ch until ch is closed, or
  the specified timeout period expires."
  [ch timeout-ms]
  (let [out-ch (chan)
        timeout-ch (timeout timeout-ms)]
    (go>
     (loop [[v c] (alts! [ch timeout-ch])]
       (when v
         (put! out-ch v)
         (recur (alts! [ch timeout-ch]))))
     (close! out-ch))
    out-ch))

(defmacro go-logged
  [& body]
  `(go>
    (try
      ~@body
      (catch Throwable e#
        ;; (errorf e# "Unexpected exception terminating go block")
        (errorf (clojure.stacktrace/root-cause e#)
                "Unexpected exception terminating go block")))))

(ann map-async
  (All [x y]
    [[y -> x] (Seqable y) AnyInteger
     -> (Seqable (ReadOnlyPort
                  '[(Nilable x) (Nilable (ErrorMap y))]))]))
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
                 [nil {:error {:exception e}
                       :target i}]))))
    (for> :- (ReadOnlyPort '[(Nilable x) (Nilable (ErrorMap y))])
          [i :- y s]
      (timeout-chan
       (thread
        (try
          [(f i) nil]
          (catch Throwable e [nil {:error {:exception e} :target i}])))
       timeout-ms))))
