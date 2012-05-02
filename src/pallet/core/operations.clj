(ns pallet.core.operations
  "Built in operations"
  (:require
   [pallet.core.primitives :as primitives]
   [pallet.core.api :as api])
  (:use
   [pallet.environment :only [environment]]
   [pallet.algo.fsmop :only [dofsm result reduce*]]
   [pallet.utils :only [apply-map]]))

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
  [service-state plan-state environment groups phase]
  (dofsm phase-executor
    [[action-plans plan-state] (result
                                ((api/action-plans-for-phase
                                  service-state environment
                                  :group-nodes groups phase)
                                 plan-state))
     results (primitives/execute-action-plans
              service-state plan-state environment
              :group-nodes action-plans)]
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
     nodes-to-add         (result (api/nodes-to-add group-deltas))
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           (api/environment-execution-settings
                            environment)
                           :group-node-list nodes-to-remove
                           :destroy-server)
     results              (primitives/remove-group-nodes
                           compute-service nodes-to-remove)
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           (api/environment-execution-settings environment)
                           :group (api/groups-to-remove group-deltas)
                           :destroy-group)
     [results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           (api/environment-execution-settings environment)
                           :group (api/groups-to-create group-deltas)
                           :create-group)
     new-nodes            (primitives/create-group-nodes
                           compute-service (environment compute-service)
                           nodes-to-add)]
    [new-nodes
     (api/service-state-with-nodes
      service-state (zipmap (keys nodes-to-add) new-nodes))
     plan-state]))

;;; Top level operations
(defn lift
  [node-set & {:keys [compute phase prefix middleware all-node-set environment]
               :as options}]
  (let [groups (if (map? node-set) [node-set] node-set)
        phases (if (keyword phase) [phase] [:configure])
        settings-groups (concat groups all-node-set)
        environment (pallet.environment/environment compute)]
    (dofsm lift
      [service-state (primitives/service-state compute settings-groups)
       [results plan-state] (primitives/build-and-execute-phase
                             service-state plan-state environment
                             (api/environment-execution-settings environment)
                             :group-nodes settings-groups :settings)
       [results plan-state] (reduce*
                             (fn reducer [[result plan-state] phase]
                               (primitives/build-and-execute-phase
                                service-state plan-state environment
                                (api/environment-execution-settings environment)
                                :group-nodes groups phase))
                             [[] {}]
                             (remove #{:settings} phases))]
      results)))

(defn execute-phase-with-image-user
  [service-state environment groups plan-state phase]
  (dofsm execute-phase-with-image-user
    [[results plan-state] (primitives/build-and-execute-phase
                           service-state plan-state environment
                           (api/environment-image-execution-settings
                            environment)
                           :group-nodes groups phase)]
  results))

(defn converge
  [group-spec->count & {:keys [compute blobstore user phase prefix middleware
                               all-nodes all-node-set environment]
                        :as options}]
  (let [groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        settings-groups (concat groups all-node-set)
        environment (pallet.environment/environment compute)]
    (dofsm converge
      [[new-nodes service-state plan-state] (node-count-adjuster
                                             compute groups {})
       result (execute-phase-with-image-user service-state environment
                groups plan-state :bootstrap)]
      [service-state result])))
