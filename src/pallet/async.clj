(ns pallet.async
  "Generally useful async functions"
  (:require
   [clojure.core.typed :refer [ann for> AnyInteger Nilable Seqable]]
   [clojure.core.typed.async :refer [go> ReadOnlyPort]]
   [clojure.core.async
    :refer [<!! >! alts! chan close! go go-loop put! thread timeout]]
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

(defmacro go-tuple
  "Provides a go macro which writes the result of it's body the
  channel, ch.  The body's value is expected to be a tuple. In the
  case of normal execution the tuple's first value is the normal
  return value, and the second is nil.  If an exception is thrown by
  the body, the tuple's first value is nil, and the exception is
  returned in the second value."
  [ch & body]
  `(go>
    (let [ch# ~ch]
      (try
        (let [r# (do ~@body)]
          (assert (sequential? r#) "Return value should be a tuple")
          (>! ch# r#))
        (catch Throwable e#
          (>! ch# [nil e#]))))))

(defn thread-fn
  "Execute function f in a new thread, return a channel for the result.
Return a tuple of [function return value, exception], where only one
of the values will be non-nil."
  [f]
  (thread
   (try
     [(f) nil]
     (catch Throwable t
       [nil t]))))

(defn map-thread
  "Apply f to each element of coll, using a separate thread for each element.
  Return a non-lazy sequence of channels for the results."
  [f coll]
  (doall (map #(thread-fn (fn [] (f %))) coll)))


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

(defn from-chan
  "Return a lazy sequence which contains all values from a channel, c."
  [c]
  (lazy-seq
   (let [v (<!! c)]
     (if-not (nil? v)
       (cons v (from-chan c))))))

;; like core.async/pipe, but returns the channel for the go-loop
;; in the pipe, so we can synchronise on the pipe completing
(defn pipe
  "Takes elements from the from channel and supplies them to the to
  channel. By default, the to channel will be closed when the
  from channel closes, but can be determined by the close?
  parameter."
  ([from to] (pipe from to true))
  ([from to close?]
     (go-loop []
      (let [v (<! from)]
        (if (nil? v)
          (when close? (close! to))
          (do (>! to v)
              (recur)))))))

(defn concat-chans
  "Concatenate values from all of the channels, chans, onto the to channel.
  By default, the to channel will be closed when all values have been
  written, but this can be controlled by the close? argument.
  Ordering of values is from a single chan is preserved, but the channels
  may be sampled in any order."
  ([chans to close?]
     (go-logged
      (doseq [i (for [c chans] (pipe c to false))]

        (<! i))
      (when close?
        (close! to))))
  ([chans to]
     (concat-chans chans to true)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (go-tuple 1))
;; End:
