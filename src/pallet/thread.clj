(ns pallet.thread
  "Thread executor primitives"
  (:require
   [clojure.tools.logging :as logging])
  (:import
   java.io.IOException
   (java.util.concurrent
    Callable Future ExecutorService Executors ScheduledExecutorService
    ScheduledFuture TimeUnit ThreadFactory
    CancellationException ExecutionException TimeoutException)))

(alter-var-root #'*warn-on-reflection* (constantly true))

(defn ^ThreadGroup thread-group
  "Returns a named thread group, under the current thread group."
  [^String thread-group-name]
  (ThreadGroup. thread-group-name))

(defn ^ThreadGroup current-thread-group
  "Returns the current thread group"
  []
  (.. (Thread/currentThread) getThreadGroup))

(defn ^ThreadGroup find-thread-group
  "Returns the named thread group in the current-thread-group"
  [thread-group-name]
  (let [current-tg (current-thread-group)
        thread-groups (make-array
                       ThreadGroup (* 2 (.activeGroupCount current-tg)))
        name-matches? (fn [^ThreadGroup tg]
                        (and tg (= thread-group-name (.getName tg))))]
    (.enumerate current-tg thread-groups)
    (first (filter name-matches? thread-groups))))

(defn ^ThreadFactory thread-factory
  "Creates a thread factory."
  [{:keys [thread-group thread-group-name prefix daemon]
    :or {prefix "pallet" daemon true}}]
  (let [^ThreadGroup thread-group (or thread-group
                                      (and thread-group-name
                                           (or (find-thread-group
                                                thread-group-name)
                                               (pallet.thread/thread-group
                                                thread-group-name)))
                                      (current-thread-group))]
    (proxy [ThreadFactory] []
      (newThread [^Runnable r]
        (let [thread (Thread. thread-group r)]
          (.setName thread (str prefix "-" (.getId thread)))
          (.setDaemon thread daemon)
          thread)))))

(defn ^ExecutorService executor
  "Create a thread pool executor.

`:pool-size`
: specifies the executor pool size (number of threads).

`:scheduled`
: flag for whether the executor should allow scheduling

`:daemon`
: a boolean to specify if threads should be daemon threads. Defaults true.

`:prefix`
: a prefix for the name of threads created for this executor

`:thread-group`
: specifies the thread group to use for threads created for this
  executor. Defaults to the current thread group. You can not specify
  `:thread-group-name` if you specify `:thread-group`.

`:thread-group-name`
: specifies a name for the thread group to use for threads created for this
  executor. Creates the thread group if it doesn't already exist. You can not
  specify `:thread-group` if you specify `:thread-group-name`."
  [{:keys [prefix daemon thread-group thread-group-name pool-size scheduled]
    :as options}]
  (when (and thread-group thread-group-name)
    (throw
     (Exception. "Can not specify both :thread-group-name and :thread-group")))
  (let [thread-factory (thread-factory options)]
    (cond
      pool-size (cond
                  (= pool-size 1) (if scheduled
                                    (Executors/newSingleThreadScheduledExecutor
                                     thread-factory)
                                    (Executors/newSingleThreadExecutor
                                     thread-factory))
                  :else (if scheduled
                                    (Executors/newScheduledThreadPool
                                     (Integer. pool-size) thread-factory)
                                    (Executors/newFixedThreadPool
                                     (Integer. pool-size) thread-factory)))
      scheduled (throw
                 (Exception. "Must specify pool size for scheduled executors"))
      :else (Executors/newCachedThreadPool thread-factory))))

(defmacro with-executor [])

(defmacro with-executor
  "bindings => [name executor ...]

  Evaluates body in a try expression with names bound to the values of the
  executor, and a finally clause that calls (.shutdown name) on each name in
  reverse order."
  {:indent 1}
  [bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally
                                  (. ~(bindings 0) shutdown))))
    :else (throw (IllegalArgumentException.
                   "with-open only allows Symbols in bindings"))))

(defn execute
  "Execute the given function `f` on the executor service `executor`."
  [^ExecutorService executor ^Callable f]
  (.submit executor f))

(def time-units
  {:days TimeUnit/DAYS
   :hours TimeUnit/HOURS
   :us TimeUnit/MICROSECONDS
   :ms TimeUnit/MILLISECONDS
   :mins TimeUnit/MINUTES
   :ns TimeUnit/NANOSECONDS
   :s TimeUnit/SECONDS})

(defn ^ScheduledFuture execute-after
  "Execute the given function `f` on the executor service `executor` after
a delay specified by `delay`. `delay-units` is one of :days, :hours, :mins,
:s, :ms, :us or :ns."
  ([^ScheduledExecutorService executor ^Callable f delay delay-units]
     (let [time-unit (time-units delay-units)]
       (when-not time-unit
         (throw (Exception. (str "Unknown delay-units: " delay-units))))
       (.schedule executor f (Long. delay) time-unit)))
  ([executor f delay]
     (execute-after executor f delay :ms)))

(defn ^ScheduledFuture execute-fixed-rate
  "Execute the given function `f` on the executor service `executor` regularly
with a period specified by `period`. An `initial-delay` may be specified.
`delay-units` is one of :days, :hours, :mins, :s, :ms, :us or :ns."
  ([^ScheduledExecutorService executor ^Runnable f period delay-units
    initial-delay]
     (let [time-unit (time-units delay-units)]
       (when-not time-unit
         (throw (Exception. (str "Unknown delay-units: " delay-units))))
       (.scheduleAtFixedRate executor f initial-delay period time-unit)))
  ([executor f period delay-units]
     (execute-every executor f period delay-units 0)))

(defn ^ScheduledFuture execute-fixed-delay
  "Execute the given function `f` on the executor service `executor` regularly
with a delay specified by `delay`. An `initial-delay` may be specified.
`delay-units` is one of :days, :hours, :mins, :s, :ms, :us or :ns."
  ([^ScheduledExecutorService executor ^Runnable f delay delay-units
    initial-delay]
     (let [time-unit (time-units delay-units)]
       (when-not time-unit
         (throw (Exception. (str "Unknown delay-units: " delay-units))))
       (.scheduleWithFixedDelay executor f initial-delay delay time-unit)))
  ([executor f period delay-units]
     (execute-every executor f period delay-units 0)))
