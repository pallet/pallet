(ns pallet.utils.async
  "Generally useful async functions"
  (:require
   [clojure.core.async.impl.protocols :refer [Channel]]
   [clojure.core.typed :refer [ann for> AnyInteger Nilable Seqable]]
   [clojure.core.typed.async :refer [go> ReadOnlyPort WriteOnlyPort]]
   [clojure.core.async
    :refer [<!! >! alts! alts!! chan close! go go-loop put! thread timeout]]
   [clojure.tools.logging :refer [debugf errorf]]
   [pallet.core.types :refer [ErrorMap]]
   [pallet.utils :refer [combine-exceptions]]))


;;; # Helpers for external protocols
(defn ^:no-check channel? [x]
  (satisfies? Channel x))

(defmacro go-logged
  "Provides a go macro, where any exception thrown by body is logged."
  [& body]
  `(go>
    (try
      ~@body
      (catch Throwable e#
        ;; (errorf e# "Unexpected exception terminating go block")
        (errorf (clojure.stacktrace/root-cause e#)
                "Unexpected exception terminating go block")))))

;; This is like core.async/pipe, but returns the channel for the
;; go-loop in the pipe, so we can synchronise on the pipe completing
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

(ann pipe-timeout (All [x]
                    [(ReadOnlyPort x) AnyInteger (WriteOnlyPort x)
                     -> (ReadOnlyPort x)]))
(defn pipe-timeout
  "Copy values from in-ch to out-ch until ch is closed, or the
  specified timeout period expires.  If a timeout occurs, timeout-val
  will be written to the output channel.  The out-ch will be closed."
  [in-ch timeout-ms timeout-val out-ch]
  (let [timeout-ch (timeout timeout-ms)]
    (go-loop [[v c] (alts! [in-ch timeout-ch])]
      (if v
        (do
          (put! out-ch v)
          (recur (alts! [in-ch timeout-ch])))
        (put! out-ch timeout-val)))
    (close! out-ch)))

(defn from-chan
  "Return a lazy sequence which contains all values from a channel, c."
  [c]
  (lazy-seq
   (let [v (<!! c)]
     (if-not (nil? v)
       (cons v (from-chan c))))))

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


;;; # Result Exception Tuples

;;; In order to assist in consistent reporting of exceptions across
;;; asynchronous go blocks, we can use result exception tuples
;;; (rex-tuples) as the values into channels.

;;; A rex-tuple is a sequence that contains a result value, and an
;;; optional exception value.


(defmacro go-try
  "Provides a go macro which executes its body inside a try/catch block.
  If an exception is thrown by the body, a rex-tuple is written to the
  channel ch.  Returns the channel for the go-block.

  NB. the channel, ch, should be buffered if the caller is going to
  block on the returned channel before reading ch."
  [ch & body]
  `(go
    (try
      ~@body
      (catch Throwable e#
        (>! ~ch [nil e#])))))

(defn thread-fn
  "Execute the zero-arity function, f, in a new thread, returning a
 channel for the result.  The result will be a rex-tuple, where the
 result is the result of calling the function f."
  [f]
  (thread
   (try
     [(f) nil]
     (catch Throwable t
       [nil t]))))

(defn map-thread
  "Apply zero-arity function, f, to each element of coll, using a
  separate thread for each element.  Return a non-lazy sequence of
  channels for the results."
  [f coll]
  (doall (map #(thread-fn (fn [] (f %))) coll)))

(defn reduce-results
  "Reduce the results of a sequence of [result exception] tuples read
  from channel in-ch, writing a single [result exception] to out-ch,
  where the result is the sequence of all the read results, and the
  exception is a composite exception of all the read exceptions."
  [in-ch out-ch]
  (go-try out-ch
    (loop [results []
           exceptions []]
      (if-let [[r e] (<! in-ch)]
        (if e
          (recur results (conj exceptions e))
          (recur (conj results r) exceptions))
        (>! out-ch [results (if-let [e (seq exceptions)]
                              (combine-exceptions e))])))))

(defn chain-and
  "Given a channel, in-ch, that will return a series of [result,
  exception] tuples, reduce the tuples into a single result tuple, and
  forward it to ch.  If there is no exception in the tuples, execute
  the async function, f, passing ch as the sole argument."
  [in-ch f ch]
  (go-try ch
    (let [[results e :as result] (<! in-ch)]
      (>! ch result)
      (if-not e
        (f ch)))))

(defn exec-operation
  "Execute the channel.  If `:async` is true, simply returns the
  channel, otherwise reads a value from the channel using the timeout
  values if specified."
  [chan {:keys [async timeout-ms timeout-val]}]
  (cond
   async chan
   timeout-ms (let [[[res e] _] (alts!! [chan (timeout timeout-ms)])]
                (when e (throw e))
                res)
   :else (let [[res e] (<!! chan)]
           (when e (throw e))
           res)))

(defn map-chan
  "Apply a function, f, to values from the from channel, writing the
  return to the to channel. Closes the to channel when the from
  channel closes."
  [from f to]
  (go-try
   (loop []
     (let [x (<! from)]
       (when-not (nil? x)
         (>! to (f x))
         (recur)))
     (close! to))))
