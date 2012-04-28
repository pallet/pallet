(ns pallet.core.operations
  "Built in operations"
  (:require
   [pallet.core.primitives :as primitives]
   [pallet.core.api :as api])
  (:use
   [pallet.environment :only [environment]]
   [pallet.algo.fsmop :only [dofsm result]]))

;;; Some maybe not so useful operations
(defn service-state-builder
  [compute-service groups]
  (dofsm service-state-builder
    [service-state (primitives/service-state compute-service groups)]
    service-state))

(defn action-plans-builder
  [compute-service groups phase]
  (dofsm action-plans-builder
    [service-state (primitives/service-state compute-service groups)]
    ((api/action-plans-for-phase
      service-state (environment compute-service) groups phase)
     {})))

(defn group-delta-calculator
  "Calculate node deltas"
  [compute-service groups]
  (dofsm node-count-adjuster
    [service-state (primitives/service-state compute-service groups)]
    (api/group-deltas service-state groups)))

;;; Basic operations - probably too low level for most

(defn phase-executor
  "Execute a single phase across all the specified groups."
  [compute-service plan-state groups phase]
  (dofsm phase-executor
    [service-state (primitives/service-state compute-service groups)
     [action-plans plan-state] (result
                                ((api/action-plans-for-phases
                                  service-state (environment compute-service)
                                  groups phase)
                                 plan-state))
     results (primitives/execute-action-plans
              service-state plan-state environment action-plans)]
    results))

(defn phases-executor
  "Execute multiple phases in parallel across all the specified groups."
  [compute-service plan-state groups phases]
  (dofsm phases-executor
    [service-state (primitives/service-state compute-service groups)
     [action-plans plan-state] (result
                                ((api/action-plans-for-phases
                                  service-state (environment compute-service)
                                  groups phases)
                                 plan-state))
     results (primitives/execute-action-plans
              service-state plan-state environment action-plans)]
    results))

(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them."
  [compute-service groups plan-state]
  (dofsm node-count-adjuster
    [service-state        (primitives/service-state compute-service groups)
     group-deltas         (result (api/group-deltas service-state groups))
     nodes-to-remove      (result (api/nodes-to-remove
                                   service-state group-deltas))
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           :group-node-list nodes-to-remove
                           :destroy-server)
     results              (primitives/remove-group-nodes
                           compute-service nodes-to-remove)
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           :group (api/groups-to-remove group-deltas)
                           :destroy-group)
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           :group (api/groups-to-create group-deltas)
                           :create-group)
     results              (primitives/create-group-nodes
                           compute-service (environment compute-service)
                           (api/nodes-to-add group-deltas))]
    results))
