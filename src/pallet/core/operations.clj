(ns pallet.core.operations
  "Built in operations"
  (:require
   [pallet.operation.primitives :as primitives])
  (:use
   [pallet.core.api :only [build-action-plans]]
   [pallet.environment :only [environment]]
   [pallet.operations :only [operation]]
   [pallet.operate :only [result]]))

(def service-state
  (operation action-plans-for-phase [compute-service groups]
    [service-state (primitives/service-state compute-service groups)]
    service-state))

(def action-plans-for-phase
  (operation action-plans-for-phase [compute-service groups phase]
    [service-state (primitives/service-state compute-service groups)
     [action-plans service-state] (result
                                   ((build-action-plans
                                     service-state (environment compute-service)
                                     phase groups)
                                    {}))]
    action-plans))

(def execute-phase
  (operation action-plans-for-phase [compute-service groups phase]
    [service-state (primitives/service-state compute-service groups)
     [action-plans plan-state] (result
                                ((build-action-plans
                                  service-state (environment compute-service)
                                  phase groups)
                                 {}))
     results (primitives/execute-action-plans
              service-state plan-state environment action-plans)]
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
;;                   (build-action-plan
;;                    service-state group-state environment
;;                    node (get-in group [:phases phase]))]
;;            action-plan)

;;      ;; or.....

;;      action-plans
;;      (for* [node nodes
;;             :let [[service-state group-state action-plan result]
;;                   (build-action-plan
;;                    service-state group-state environment
;;                    node (get-in group [:phases phase]))]
;;             :thread [service-state group-state]]
;;            action-plan)

;;      ;; non-leaky for
;;      [service-state group-state action-plans]
;;      (for* [node nodes
;;             :thread [service-state group-state]]
;;            [[service-state group-state action-plan result]
;;             (build-action-plan
;;              service-state group-state environment
;;              node (get-in group [:phases phase]))]
;;            [service-state group-state action-plan])

;;      ;; or.....

;;      [service-state group-state action-plans]
;;      (reduce*
;;       (operation [[service-state group-state action-plans] node]
;;           [[service-state group-state action-plan result]
;;            (build-action-plan
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
;;            (build-action-plan
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
;;       (build-action-plan
;;        service-state group-state environment
;;        node (get-in group [:phases phase])))
