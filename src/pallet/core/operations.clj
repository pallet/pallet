(ns pallet.core.operations
  "Built in operations"
  (:require
   [pallet.operation.primitives :as primitives]
   [pallet.core.api :as api])
  (:use

   [pallet.environment :only [environment]]
   [pallet.operations :only [operation]]
   [pallet.operate :only [result]]))

;;; Some maybe not so useful operations
(def service-state-builder
  (operation service-state-builder [compute-service groups]
    [service-state (primitives/service-state compute-service groups)]
    service-state))

(def action-plans-builder
  (operation action-plans-builder [compute-service groups phase]
    [service-state (primitives/service-state compute-service groups)
     [action-plans service-state] (result
                                   ((api/action-plans-for-phase
                                     service-state (environment compute-service)
                                     groups phase)
                                    {}))]
    action-plans))

(def group-delta-calculator
  "Calculate node deltas"
  (operation node-count-adjuster [compute-service groups plan-state]
    [service-state        (primitives/service-state compute-service groups)
     group-deltas         (result (api/group-deltas service-state groups))]
    group-deltas))

;;; Basic operations - probably too low level for most

(def phase-executor
  "Execute a single phase across all the specified groups."
  (operation phase-executor [compute-service plan-state groups phase]
    [service-state (primitives/service-state compute-service groups)
     [action-plans plan-state] (result
                                ((api/action-plans-for-phases
                                  service-state (environment compute-service)
                                  groups phases)
                                 plan-state))
     results (primitives/execute-action-plans
              service-state plan-state environment action-plans)]
    results))

(def phases-executor
  "Execute multiple phases in parallel across all the specified groups."
  (operation phases-executor [compute-service plan-state groups phases]
    [service-state (primitives/service-state compute-service groups)
     [action-plans plan-state] (result
                                ((api/action-plans-for-phases
                                  service-state (environment compute-service)
                                  groups phases)
                                 plan-state))
     results (primitives/execute-action-plans
              service-state plan-state environment action-plans)]
    results))

(def node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them."
  (operation node-count-adjuster [compute-service groups plan-state]
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







;; (def lift-groups-phase
;;   (operation lift [compute-service group phase]
;;     [service-state (service-state compute-service groups)
;;      nodes (result (nodes-in-group service-state group))
;;      group-state (result {})
;;      environment (result {})

;;      action-plans
;;      (for* [node nodes]
;;            :let! [[service-state group-state action-plan result]
;;                   (action-plan
;;                    service-state group-state environment
;;                    node (get-in group [:phases phase]))]
;;            action-plan)

;;      ;; or.....

;;      action-plans
;;      (for* [node nodes
;;             :let [[service-state group-state action-plan result]
;;                   (action-plan
;;                    service-state group-state environment
;;                    node (get-in group [:phases phase]))]
;;             :thread [service-state group-state]]
;;            action-plan)

;;      ;; non-leaky for
;;      [service-state group-state action-plans]
;;      (for* [node nodes
;;             :thread [service-state group-state]]
;;            [[service-state group-state action-plan result]
;;             (action-plan
;;              service-state group-state environment
;;              node (get-in group [:phases phase]))]
;;            [service-state group-state action-plan])

;;      ;; or.....

;;      [service-state group-state action-plans]
;;      (reduce*
;;       (operation [[service-state group-state action-plans] node]
;;           [[service-state group-state action-plan result]
;;            (action-plan
;;             service-state group-state environment
;;             node (get-in group [:phases phase]))]
;;         service-state group-state (conj action-plans action-plan))
;;       [service-state group-state []]
;;       nodes)

;;      ;; or.....

;;      ;; or.....

;;      action-plans
;;      (reduce
;;       (operation
;;           [[service-state group-state action-plan result]
;;            (action-plan
;;             service-state group-state environment
;;             node (get-in group [:phases phase]))]
;;           action-plan)
;;       [node nodes])



;;      results
;;      (pfor* [[node action-plan] (map identity nodes action-plans)
;;              :let! [[service-state group-state result]
;;                     (execute-action-plan
;;                      service-state group-state environment
;;                      node action-plan)]]
;;             result)]

;;     results))



;;      [service-state group-state action-plans results]
;;      ;; service-state and group-state need to be threaded, while action-plan
;;      ;; needs to be built into a sequence.

;;      ;; for service-state and group-state, we could assume step outputs are the
;;      ;; same as the chain* result
;;      (chain* ;; :thread [service-state group-state] :collect [action-plan]
;;       [node nodes]

;;       [service-state group-state ^:collect action-plan ^:collect result]
;;       (action-plan
;;        service-state group-state environment
;;        node (get-in group [:phases phase])))
