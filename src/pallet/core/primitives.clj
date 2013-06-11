(ns pallet.core.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.algo.fsm.fsm-dsl
    :refer [event-handler
            event-machine-config
            fsm-name
            on-enter
            state
            valid-transitions]]
   [pallet.algo.fsmop
    :refer [dofsm execute fail-reason map* result update-state wait-for]]
   [pallet.core.api :as api]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :refer [id]]))
;;; ## Wrap non-FSM functions in simple FSM

;;; TODO: Provide support for controlling retry count, standoff, etc, although
;;; this might be better added externally.
(defn async-fsm
  "Returns a FSM specification for running the specified function in a future.
  Assumes failures in the underlying function cause an exception to be thrown,
  and that the function takes no arguments."
  [f]
  (let [async-f (atom nil)]
    (letfn [(running [state event event-data]
              (case event
                :success (update-state
                          state :completed assoc :result event-data)
                :fail (update-state
                       state :failed assoc :fail-reason event-data)
                :abort (do
                         (when-let [async-f @async-f]
                           (future-cancel async-f))
                         (update-state
                          state :aborted assoc :fail-reason event-data))))
            (run-async [{:keys [em state-data] :as state}]
              (let [event (:event em)
                    f-runner (fn async-fsm []
                               (try
                                 (event :success (f))
                                 (catch Throwable e
                                   (logging/warn e "async-fsm failed")
                                   (event :fail {:exception e}))))]
                (reset! async-f (execute f-runner))))]
      (event-machine-config
        (fsm-name (str f))
        (state :running
          (valid-transitions :completed :failed :aborted)
          (on-enter run-async)
          (event-handler running))))))

;;; ## Node state
(defn set-state-for-node
  "Sets the boolean `state-name` flag on `node`."
  [state-name node]
  (logging/debugf "set-state-for-node %s %s" state-name (:id (:node node)))
  (async-fsm (partial api/set-state-for-node state-name node)))

(defn set-state-for-nodes
  "Sets the boolean `state-name` flag on `nodes`."
  [state-name nodes]
  (logging/debugf "set-state-for-nodes %s %s"
                  state-name (mapv (comp id :node) nodes))
  (map* (map (partial set-state-for-node state-name) nodes)))

;;; ## Compute service state
(defn service-state
  "Define an operation that builds a representation of the available nodes."
  [compute groups]
  (async-fsm (partial api/service-state compute groups)))


;;; ## Action plan execution
(defn execute-action-plan
  "Executes an action-plan on the specified node."
  [service-state plan-state environment execution-settings-f
   {:keys [action-plan phase target-type target] :as action-plan-map}]
  {:pre [action-plan-map action-plan target]}
  (let [{:keys [user executor executor-status-fn]} (execution-settings-f
                                                    environment target)]
    (async-fsm
     (partial
      api/execute-action-plan
      service-state
      plan-state environment user
      executor executor-status-fn action-plan-map))))

(defn execute-action-plans
  "Execute `action-plans`, a sequence of action-plan maps.
   `execution-settings-f` is a function of target, that returns a map with
   :user, :executor and :executor-status-fn keys."
  [service-state plan-state environment execution-settings-f action-plans]
  (dofsm execute-action-plans
    [results (map*
              (map
               (partial
                execute-action-plan service-state plan-state
                environment execution-settings-f)
               action-plans))]
    [results
     (reduce (partial merge-keys {}) plan-state (map :plan-state results))]))

(defn build-and-execute-phase
  "Build and execute the specified phase.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list."
  [service-state plan-state environment phase targets execution-settings-f]
  {:pre [phase]}
  (logging/debugf
   "build-and-execute-phase %s on %s target(s)" phase (count targets))
  (logging/tracef "build-and-execute-phase plan-state %s" plan-state)
  (logging/tracef "build-and-execute-phase environment %s" environment)
  (let [[action-plans plan-state]
        ((api/action-plans service-state plan-state environment phase targets)
         plan-state)]
    (logging/tracef
     "build-and-execute-phase execute %s actions %s"
     (vec (map (comp count :action-plan) action-plans)) plan-state)
    (logging/tracef "build-and-execute-phase plan-state %s" plan-state)
    (execute-action-plans
     service-state plan-state environment execution-settings-f action-plans)))

(defn execute-and-flag
  "Return a phase execution function, that will exacute a phase on nodes that
  don't have the specified state flag set. On successful completion the nodes
  have the state flag set."
  ([state-flag execute-f]
     (logging/tracef "execute-and-flag state-flag %s" state-flag)
     (fn execute-and-flag
       [service-state plan-state environment phase targets execution-settings-f]
       (dofsm execute-and-flag
         [results (result (logging/tracef "execute-and-flag %s" state-flag))
          [results plan-state] (execute-f
                                service-state plan-state environment phase
                                (filter
                                 (complement (api/has-state-flag? state-flag))
                                 targets)
                                execution-settings-f)
          _ (result (logging/tracef
                     "execute-and-flag %s setting flag" state-flag))
          _ (set-state-for-nodes
             state-flag (map :target (remove :errors results)))
          _ (result (logging/tracef "execute-and-flag %s done" state-flag))]
         [results plan-state])))
  ([state-flag]
     (execute-and-flag state-flag build-and-execute-phase)))

(defn execute-on-filtered
  "Return a phase execution function, that will exacute a phase on nodes that
  have the specified state flag set."
  [filter-f execute-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [service-state plan-state environment phase targets execution-settings-f]
    (dofsm execute-on-filtered
      [results (result (logging/tracef "execute-on-filtered %s" execute-f))
       [results plan-state] (execute-f
                             service-state plan-state environment phase
                             (filter-f targets)
                             execution-settings-f)
       _ (result(logging/tracef "execute-on-filtered ran"))]
      [results plan-state])))

(defn execute-on-flagged
  "Return a phase execution function, that will exacute a phase on nodes that
  have the specified state flag set."
  ([state-flag execute-f]
     (logging/tracef "execute-on-flagged state-flag %s" state-flag)
     (execute-on-filtered
      #(filter (api/has-state-flag? state-flag) %)
      execute-f))
  ([state-flag]
     (execute-on-flagged state-flag build-and-execute-phase)))

(defn execute-on-unflagged
  "Return a phase execution function, that will exacute a phase on nodes that
  have the specified state flag set."
  ([state-flag execute-f]
     (logging/tracef "execute-on-flagged state-flag %s" state-flag)
     (execute-on-filtered
      #(filter (complement (api/has-state-flag? state-flag)) %)
      execute-f))
  ([state-flag]
     (execute-on-unflagged state-flag build-and-execute-phase)))

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
               :phase-execution-f (execute-and-flag :bootstrapped)}})

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
