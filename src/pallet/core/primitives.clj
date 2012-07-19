(ns pallet.core.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.core.api :as api])
  (:use
   [pallet.algo.fsmop :only [dofsm execute update-state map*]]
   [pallet.algo.fsm.fsm-dsl :only
    [event-handler event-machine-config fsm-name initial-state on-enter state
     state-driver valid-transitions]]
   [pallet.map-merge :only [merge-keys]]))

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
                    f-runner (fn []
                               (try
                                 (event :success (f))
                                 (catch Exception e
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
  (async-fsm (partial api/set-state-for-node state-name node)))

(defn set-state-for-nodes
  "Sets the boolean `state-name` flag on `nodes`."
  [state-name nodes]
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
                                                    target)]
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
  [service-state plan-state environment execution-settings-f targets phase]
  {:pre [phase]}
  (logging/debugf
   "build-and-execute-phase %s on %s target(s)" phase (count targets))
  (logging/tracef "build-and-execute-phase plan-state %s" plan-state)
  (let [[action-plans plan-state]
        ((api/action-plans service-state environment phase targets)
         plan-state)]
    (logging/tracef
     "build-and-execute-phase execute %s actions %s"
     (vec (map (comp count :action-plan) action-plans)) plan-state)
    (logging/tracef "build-and-execute-phase plan-state %s" plan-state)
    (execute-action-plans
     service-state plan-state environment execution-settings-f action-plans)))

(defn execute-phase-with-image-user
  [service-state environment targets plan-state phase]
  (logging/tracef "execute-phase-with-image-user plan-state %s" plan-state)
  (dofsm execute-phase-with-image-user
    [[results plan-state] (build-and-execute-phase
                           service-state plan-state environment
                           (api/environment-image-execution-settings
                            environment)
                           targets phase)]
    [results plan-state]))

(defn execute-on-unflagged
  "Execute a function of service-state on nodes that don't have the specified
  state flag set. On successful completion the nodes have the state flag set."
  [targets execute-f state-flag]
  (dofsm execute-on-unflagged
    [[results plan-state] (execute-f
                           (filter
                            (complement (api/has-state-flag? state-flag))
                            targets))
     _ (set-state-for-nodes
        state-flag (map :target (remove :errors results)))]
    [results plan-state]))

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
