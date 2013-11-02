(ns pallet.core.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.core.async :as async
    :refer [>!! alt!! alts!! close! chan go sliding-buffer thread timeout]]
   [clojure.tools.logging :as logging]
   [pallet.async :refer [timeout-chan]]
   [pallet.core.api :as api]
   [pallet.core.protocols :as impl :refer [Status StatusUpdate DeliverValue]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :refer [id]]
   [pallet.utils :refer [deep-merge]]))

(deftype AsyncOperation
    [status status-chan completed-promise close-status-chan?]
  Status
  (impl/status [_] @status)
  StatusUpdate
  (impl/status! [_ v]
    (>!! status-chan v)
    (swap! status conj v))
  DeliverValue
  (impl/value! [_ v]
    (>!! status-chan {:op :completed :value v})
    (deliver completed-promise v)
    (when close-status-chan?
      (close! status-chan)))
  clojure.lang.IDeref
  (deref [op] (deref completed-promise))
  clojure.lang.IBlockingDeref
  (deref [op timeout-ms timeout-val]
    (deref completed-promise timeout-ms timeout-val))
  clojure.lang.IPending
  (isRealized [this] (realized? completed-promise)))

(defn async-operation
  "Return a map with an operation, a status update function, and a return value
update function."
  [{:keys [close-status-chan? status-chan]
    :or {status-chan (chan (sliding-buffer 100))
         close-status-chan? true}}]
  (let [status (atom [])
        p (promise)]
    (AsyncOperation. status status-chan p close-status-chan?)))

(defn status
  "Return the status of an operation"
  [operation]
  (impl/status operation))

(defn status!
  "Add a status to an operation"
  [operation value]
  (impl/status! operation value))

(defn- value!
  "Set the result of an operation"
  [operation value]
  (impl/value! operation value))

(defn exec-operation
  "Execute a function using an operation.

The function must accept a single operation argument.

Depending on the flags returns an operation value and executes asynchronously,
or executes synchronously with an optional timeout."
  [f {:keys [async operation status-chan timeout-ms timeout-val]
      :as options}]
  (let [operation (or operation
                      (async-operation (select-keys options [:status-chan])))
        g #(do (go
                (try
                  (value! operation (f operation))
                  (catch Throwable e
                    (logging/errorf e "Error in asynch op")
                    (clojure.stacktrace/print-cause-trace e)
                    (value! operation e))))
               operation)]
    (if async
      (g)
      (if timeout-ms
        (deref (g) timeout-ms timeout-val)
        (deref (g))))))

;;; # Async
(defn- map-targets
  "Returns a channel which will return the result of applying f to
  targets, reducing the results into a tuple containing a result
  vector and a plan-state."
  [f targets plan-state]
  (logging/debugf "map-targets on %s targets" (count targets))
  (->> (map f targets)
       (async/merge)
       (async/reduce
        (fn [[results plan-state] r]
          [(conj results r) (deep-merge plan-state (:plan-state r))])
        [[] plan-state])))

;; (async/map< #(if-let [r (first %)]
;;                r
;;                (if-let [e (second %)]
;;                  {:error {:exception e}})))

;; (logging/debugf "map-targets %s" (vec r))
;; [r (reduce deep-merge plan-state (map :plan-state r))])

;; TODO make timeout configurable
;; (take-channel (count targets) (* 5 60 1000))

(defn execute-phase
  "Build and execute the specified phase.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list."
  [service-state plan-state environment phase targets execution-settings-f]
  {:pre [phase]}
  (logging/debugf
   "execute-phase %s on %s target(s)" phase (count targets))
  (logging/tracef "execute-phase plan-state %s" plan-state)
  (logging/tracef "execute-phase environment %s" environment)
  (let [f (fn b-a-e [target]
            (api/execute-phase-on-target
             service-state plan-state environment phase execution-settings-f
             target))
        targets-with-phase (filter #(api/target-phase % phase) targets)]
    (map-targets f targets-with-phase plan-state)))

(defn execute-phase-and-flag
  "Return a phase execution function, that will execute a phase on nodes that
  don't have the specified state flag set. On successful completion the nodes
  have the state flag set."
  [state-flag]
  (fn
    [service-state plan-state environment phase targets execution-settings-f]
    (logging/debugf
     "execute-phase %s on %s target(s)" phase (count targets))
    (logging/tracef "execute-phase plan-state %s" plan-state)
    (logging/tracef "execute-phase environment %s" environment)

    (let [f #(let [r (api/execute-phase-on-target
                      service-state plan-state environment phase
                      execution-settings-f %)]
               (when (some :error (:result r))
                 (api/set-state-for-node state-flag %))
               r)
          targets-with-phase (->>
                              targets
                              (filter (complement
                                       (api/has-state-flag? state-flag)))
                              (filter #(api/target-phase % phase)))]
      (map-targets f targets-with-phase plan-state))))

(defn execute-on-filtered
  "Return a phase execution function, that will execute a phase on nodes that
  have the specified state flag set."
  [filter-f execute-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [service-state plan-state environment phase targets execution-settings-f]
    (execute-f
     service-state plan-state environment phase (filter-f targets)
     execution-settings-f)))

(defn execute-on-flagged
  "Return a phase execution function, that will execute a phase on nodes that
  have the specified state flag set."
  ([state-flag execute-f]
     (logging/tracef "execute-on-flagged state-flag %s" state-flag)
     (execute-on-filtered
      #(filter (api/has-state-flag? state-flag) %)
      execute-f))
  ([state-flag]
     (execute-on-flagged state-flag execute-phase)))

(defn execute-on-unflagged
  "Return a phase execution function, that will execute a phase on nodes that
  have the specified state flag set."
  ([state-flag execute-f]
     (logging/tracef "execute-on-flagged state-flag %s" state-flag)
     (execute-on-filtered
      #(filter (complement (api/has-state-flag? state-flag)) %)
      execute-f))
  ([state-flag]
     (execute-on-unflagged state-flag execute-phase)))

(def ^{:doc "Executes on non bootstrapped nodes, with image credentials."}
  unbootstrapped-meta
  {:execution-settings-f (api/environment-image-execution-settings)
   :phase-execution-f (execute-on-unflagged :bootstrapped)})

(def ^{:doc "Executes on bootstrapped nodes, with admin user credentials."}
  bootstrapped-meta
  {:phase-execution-f (execute-on-flagged :bootstrapped)})

(def ^{:doc "The bootstrap phase is executed with the image credentials, and
only not flagged with a :bootstrapped keyword."}
  default-phase-meta
  {:bootstrap {:execution-settings-f (api/environment-image-execution-settings)
               :phase-execution-f (execute-phase-and-flag :bootstrapped)}})

;; It's not nice that this can not be in p.core.api
(defn phases-with-meta
  "Takes a `phases-map` and applies the default phase metadata and the
  `phases-meta` to the phases in it."
  [phases-map phases-meta]
  (reduce-kv
   (fn [result k v]
     (let [dm (default-phase-meta k)
           pm (get phases-meta k)]
       (assoc result k (if (or dm pm)
                         ;; explicit overrides default
                         (vary-meta v #(merge dm % pm))
                         v))))
   nil
   (or phases-map {})))

;;; ## Result predicates
(defn successful-result?
  "Filters `target-results`, a map from target to result map, for successful
  results."
  [result]
  (not (:errors result)))


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
                 (logging/errorf e "Unexpected exception")
                 (clojure.stacktrace/print-cause-trace e)
                 [nil e]))))
    (for [i s]
      (timeout-chan
       (thread
        (try
          [(f i)]
          (catch Throwable e [nil {:exception e :target i}])))
       timeout-ms))))

(defn take-channel
  "Take n items from a channel, with a specified timeout"
  [n timeout-ms channel]
  (->> (repeatedly n #(alts!! [channel (async/timeout timeout-ms)]))
       (mapv first)))

(defn create-group-nodes
  "Create nodes for groups."
  [compute-service environment group-counts]
  (logging/debugf
   "create-group-nodes %s %s %s"
   compute-service environment (vec group-counts))
  (let [r (->> (map-async
                #(api/create-nodes
                  compute-service environment (first %) (:delta (second %)))
                group-counts
                (* 5 60 1000))
               async/merge
               ;; TODO make timeout configurable
               (take-channel (count group-counts) (* 5 60 1000)))]
    (when-let [e (some second r)]
      (throw e))
    (mapcat first r)))

(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [compute-service group-nodes]
  (logging/debugf "remove-group-nodes %s" group-nodes)
  (let [r (->> (map-async
                #(api/remove-nodes compute-service (key %) (val %))
                group-nodes
                (* 5 60 1000))
               async/merge
               ;; TODO make timeout configurable
               (take-channel (count group-nodes) (* 30 1000)))]
    (when-let [e (some second r)]
      (throw e))
    (mapcat first r)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (target-exec 1))
;; End:
