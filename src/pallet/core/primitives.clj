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

;;; Provide support for controlling retry count, standoff, etc,
;;; Although this can be added externally.
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

(defn service-state
  "Define an operation that builds a representation of the available nodes."
  [compute groups]
  (async-fsm (partial api/service-state compute groups)))

(defn execute-action-plan
  "Executes an action-plan on the specified node."
  [service-state plan-state execution-settings-f target-type
   [target action-plan]]
  (let [{:keys [user executor executor-status-fn]} (execution-settings-f
                                                    target)]
    (async-fsm
     (partial
      api/execute-action-plan
      service-state plan-state user executor executor-status-fn action-plan
      target-type target))))

(defn execute-action-plans
  "Execute `action-plans` a map from an instance for target-type and an
  action-plan"
  [service-state plan-state execution-settings-f target-type
  action-plans]
  (logging/debugf "execute-action-plans %s" (keys action-plans))
  (dofsm execute-action-plans
    [results (map*
              (map
               (partial
                execute-action-plan service-state plan-state
                execution-settings-f target-type)
               action-plans))]
    [(reduce #(apply assoc %1 %2) {} (map (juxt :target :result) results))
     (reduce (partial merge-keys {}) (map :plan-state results))]))

(defn build-and-execute-phase
  "Build and execute the specified phase.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list."
  [service-state plan-state environment execution-settings-f
   target-type targets phase]
  {:pre [phase]}
  (logging/debugf "build-and-execute-phase %s %s" phase targets)
  (let [[action-plans plan-state]
        ((api/action-plans-for-phase
          service-state environment target-type targets phase)
         plan-state)
        target-type (if (= :group target-type) :group :node)]
    (logging/debugf
     "build-and-execute-phase execute %s %s" action-plans plan-state)
    (execute-action-plans
     service-state plan-state execution-settings-f target-type action-plans)))

(defn create-nodes
  "Create `count` nodes for a `group`."
  [compute-service environment group count]
  (async-fsm
   (partial api/create-nodes compute-service environment group count)))

(defn create-group-nodes
  "Create nodes for group."
  [compute-service environment group-counts]
  (map*
   (map
    #(create-nodes compute-service environment (key %) (:delta (val %)))
    group-counts)))

(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all] :as remove-node-map}]
  (logging/infof "remove-nodes %s" remove-node-map)
  (async-fsm
   (partial api/remove-nodes compute-service group remove-node-map)))

(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [compute-service group-nodes]
  (logging/infof "remove-group-nodes %s" group-nodes)
  (map* (map #(remove-nodes compute-service (key %) (val %)) group-nodes)))
