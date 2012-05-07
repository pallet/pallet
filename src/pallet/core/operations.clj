(ns pallet.core.operations
  "Built in operations"
  (:require
   [pallet.core.primitives :as primitives]
   [pallet.core.api :as api]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.environment :only [environment]]
   [pallet.algo.fsmop :only [dofsm reduce* result succeed]]
   [pallet.utils :only [apply-map]]))

(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them."
  [compute-service groups plan-state]
  (dofsm node-count-adjuster
    [service-state        (primitives/service-state compute-service groups)
     group-deltas         (result (api/group-deltas service-state groups))
     nodes-to-remove      (result (api/nodes-to-remove
                                   service-state group-deltas))
     nodes-to-add         (result (api/nodes-to-add group-deltas))
     [results1 plan-state] (primitives/build-and-execute-phase
                            service-state plan-state environment
                            (api/environment-execution-settings
                             environment)
                            :group-node-list nodes-to-remove
                            :destroy-server)
     _              (primitives/remove-group-nodes
                             compute-service nodes-to-remove)
     [results2 plan-state] (primitives/build-and-execute-phase
                            service-state plan-state environment
                            (api/environment-execution-settings environment)
                            :group (api/groups-to-remove group-deltas)
                            :destroy-group)
     [results3 plan-state] (primitives/build-and-execute-phase
                            service-state plan-state environment
                            (api/environment-execution-settings environment)
                            :group (api/groups-to-create group-deltas)
                            :create-group)
     new-nodes            (primitives/create-group-nodes
                           compute-service (environment compute-service)
                           nodes-to-add)]
    {:new-nodes new-nodes
     :old-nodes nodes-to-remove
     :service-state (->
                     service-state
                     (api/service-state-with-nodes
                       (zipmap (keys nodes-to-add) new-nodes))
                     (api/service-state-without-nodes nodes-to-remove))
     :plan-state plan-state
     :results (concat results1 results2 results3)}))

;;; ## Top level operations
(defn lift
  [groups settings-groups phases compute environment plan-state]
  (let [settings-groups (concat groups settings-groups)]
    (dofsm lift
      [service-state (primitives/service-state compute settings-groups)
       [results plan-state] (primitives/build-and-execute-phase
                             service-state plan-state environment
                             (api/environment-execution-settings environment)
                             :group-nodes settings-groups :settings)
       [results plan-state] (reduce*
                              (fn reducer [[result plan-state] phase]
                                (dofsm reduce-phases
                                  [[r ps] (primitives/build-and-execute-phase
                                           service-state plan-state environment
                                           (api/environment-execution-settings
                                            environment)
                                           :group-nodes groups phase)
                                   _ (succeed
                                      (not (some :errors r))
                                      {:phase-errors true
                                       :phase phase
                                       :results (concat result r)})]
                                  [r ps]))
                              [[] {}]
                              (remove #{:settings} phases))]
      results)))

(defn converge
  [groups settings-groups phases compute environment plan-state]
  (logging/debugf
   "converge :phase %s :groups %s :settings-groups %s"
   (vec phases)
   (vec (map :group-name groups))
   (vec (map :group-name settings-groups)))
  (let [settings-groups (concat groups settings-groups)]
    (dofsm converge
      [{:keys [new-nodes old-nodes service-state plan-state results]}
       (node-count-adjuster compute groups plan-state)

       [results1 plan-state] (primitives/build-and-execute-phase
                              service-state plan-state environment
                              (api/environment-execution-settings environment)
                              :group-nodes settings-groups :settings)
       _ (succeed
          (not (some :errors results1))
          {:phase-errors true :phase :settings :results results1})

       [results2 plan-state] (primitives/execute-on-unflagged
                              service-state
                              #(primitives/execute-phase-with-image-user
                                 % environment groups plan-state :bootstrap)
                              :bootstrapped)

       _ (succeed
          (not (some :errors results2))
          {:phase-errors true :phase :bootstrap
           :results (concat results1 results2)})

       [results3 plan-state] (reduce*
                              (fn reducer [[result plan-state] phase]
                                (dofsm reduce-phases
                                  [[r ps] (primitives/build-and-execute-phase
                                           service-state plan-state environment
                                           (api/environment-execution-settings
                                            environment)
                                           :group-nodes groups phase)
                                   _ (succeed
                                      (not (some :errors r))
                                      {:phase-errors true
                                       :phase phase
                                       :results (concat result r)})]
                                  [r ps]))
                              [[] plan-state]
                              (remove #{:settings :bootstrap} phases))]
      {:results (concat results results1 results2 results3)
       :service-state service-state
       :plan-state plan-state})))
