(ns pallet.core.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.core.async :as async
    :refer [>!! alt!! alts!! close! chan go sliding-buffer thread timeout]]
   [clojure.tools.logging :as logging]
   [pallet.core.api :as api]
   [pallet.core.protocols :as impl :refer [Status StatusUpdate DeliverValue]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :refer [id]]))

(deftype AsyncOperation
    [status status-chan completed-promise]
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
    (close! status-chan))
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
  [{:keys [status-chan] :or {status-chan (chan (sliding-buffer 100))}}]
  (let [status (atom [])
        p (promise)]
    (AsyncOperation. status status-chan p)))

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

(defn map-async
  "Apply f to each element of s in a thread per s.

Returns a sequence of the results.  Each element in the result is a
vector, containing the result of the function call as the first
element, and any exception thrown as the second element.  A nil
element in the result signifies a timeout."

  ;; TODO control the number of threads used here, or under some
  ;; configurable place - might depend on the executor being used
  ;; (eg., a message queue based executor might not need limiting),
  ;; though should probably be configurable separately to this.

  [f s {:keys [timeout] :or {timeout (* 5 60 1000)}}]
  (if false
    (let [channels (conj (doall (for [i s]
                                  (thread
                                   (try
                                     [(f i)]
                                     (catch Throwable e
                                       ;; (logging/errorf e "Error")
                                       ;; (clojure.stacktrace/print-cause-trace e)
                                       [nil e])))))
                         (async/timeout timeout))]
      (->> (repeatedly (count s) #(alts!! channels))
           (mapv first)))
    (doall (for [i s]
             (try
               [(f i)]
               (catch Throwable e
                 ;; (logging/errorf e "Error")
                 ;; (clojure.stacktrace/print-cause-trace e)
                 [nil e]))))))

(defn- map-targets
  [f targets plan-state]
  (let [r (->> (map-async f targets {})
               (map #(if-let [r (first %)]
                       r
                       (if-let [e (second %)]
                         {:error {:exception e}})))
               (filter identity))]
    [r (reduce merge plan-state (map :plan-state r))]))

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
     service-state plan-state environment phase targets execution-settings-f
     (filter-f targets))))

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

(defn create-group-nodes
  "Create nodes for groups."
  [compute-service environment group-counts]
  (logging/debugf
   "create-group-nodes %s %s %s"
   compute-service environment group-counts)
  (let [r (map-async
           #(api/create-nodes
             compute-service environment (key %) (:delta (val %)))
           group-counts
           {})]
    (when-let [e (some second r)]
      (throw e))
    (mapcat first r)))

(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [compute-service group-nodes]
  (logging/debugf "remove-group-nodes %s" group-nodes)
  (let [r (map-async
           #(api/remove-nodes compute-service (key %) (val %))
           group-nodes
           {})]
    (when-let [e (some second r)]
      (throw e))
    (mapcat first r)))

#_(
;;; ## Node count adjustment
(defn create-nodes
  "Create `count` nodes for a `group`."
  [compute-service environment group count]
  (async-fsm
   (partial api/create-nodes compute-service environment group count)))

(defn create-group-nodes
  "Create nodes for groups."
  [compute-service environment group-counts]
  (logging/debugf
   "create-group-nodes %s %s %s"
   compute-service environment group-counts)
  (dofsm create-group-nodes
    [results (map*
              (map
               #(create-nodes
                 compute-service environment (key %) (:delta (val %)))
               group-counts))]
    (apply concat results)))

(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all] :as remove-node-map}]
  (logging/debugf "remove-nodes %s" remove-node-map)
  (async-fsm
   (partial api/remove-nodes compute-service group remove-node-map)))

(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [compute-service group-nodes]
  (logging/debugf "remove-group-nodes %s" group-nodes)
  (map* (map #(remove-nodes compute-service (key %) (val %)) group-nodes)))

;;; # Exception reporting
(defn throw-operation-exception
  "If the result has a logged exception, throw it. This will block on the
   operation being complete or failed."
  [operation]
  (when-let [f (fail-reason operation)]
    (when-let [e (:exception f)]
      (throw e))))

(defn phase-errors
  "Return the phase errors for an operation"
  [operation]
  (api/phase-errors (wait-for operation)))

(defn throw-phase-errors
  [operation]
  (api/throw-phase-errors (wait-for operation)))
)

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (target-exec 1))
;; End:
