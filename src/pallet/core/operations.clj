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
  [compute-service groups targets environment plan-state]
  {:pre [compute-service]}
  (dofsm node-count-adjuster
    [group-deltas         (result (api/group-deltas targets groups))
     nodes-to-remove      (result (api/nodes-to-remove targets group-deltas))
     nodes-to-add         (result (api/nodes-to-add group-deltas))
     [results1 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            (api/environment-execution-settings environment)
                            (mapcat :nodes (vals nodes-to-remove))
                            :destroy-server)
     _ (primitives/remove-group-nodes compute-service nodes-to-remove)
     [results2 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            (api/environment-execution-settings environment)
                            (api/groups-to-remove group-deltas)
                            :destroy-group)
     [results3 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            (api/environment-execution-settings environment)
                            (api/groups-to-create group-deltas)
                            :create-group)
     new-nodes (primitives/create-group-nodes
                compute-service environment nodes-to-add)]
    {:new-nodes new-nodes
     :old-nodes (mapcat :nodes (vals nodes-to-remove))
     :targets (->> targets
                   (concat new-nodes)
                   (remove (set (mapcat :nodes (vals nodes-to-remove)))))
     :plan-state plan-state
     :results (concat results1 results2 results3)}))

;;; ## Top level operations
(defn group-nodes
  [compute groups]
  (dofsm group-nodes
    [service-state (primitives/service-state compute groups)]
    service-state))

(defn lift
  [targets phases environment plan-state]
  (logging/debugf
   "lift :phase %s :targets %s" (vec phases) (vec (map :group-name targets)))
  (dofsm lift
    [[results plan-state] (reduce*
                           (fn reducer [[results plan-state] phase]
                             (dofsm reduce-phases
                               [[r ps] (primitives/build-and-execute-phase
                                        targets plan-state environment
                                        (api/environment-execution-settings
                                         environment)
                                        targets phase)
                                results1 (result (concat results r))
                                _ (succeed
                                   (not (some :errors r))
                                   (merge
                                    {:phase-errors true
                                     :phase phase
                                     :results results1}
                                    (when-let [e (some
                                                  #(some
                                                    (comp :cause :error)
                                                    (:errors %))
                                                  results1)]
                                      {:exception e})))]
                               [results1 ps]))
                           [[] plan-state]
                           phases)]
    {:results results
     :targets targets
     :plan-state plan-state}))

(defn converge
  [groups targets phases compute environment plan-state]
  (logging/debugf
   "converge :phase %s :groups %s :settings-groups %s"
   (vec phases)
   (vec (map :group-name groups))
   (vec (map :group-name targets)))
  (dofsm converge
    [{:keys [new-nodes old-nodes targets service-state plan-state results]}
     (node-count-adjuster compute groups targets environment plan-state)

     [results1 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            (api/environment-execution-settings environment)
                            targets :settings)
     _ (succeed
        (not (some :errors results1))
        (merge
         {:phase-errors true :phase :settings :results results1}
         (when-let [e (some #(some (comp :cause :error) (:errors %)) results1)]
           {:exception e})))

     [results2 plan-state] (primitives/execute-on-unflagged
                            targets
                            #(primitives/execute-phase-with-image-user
                               service-state environment % plan-state
                               :bootstrap)
                            :bootstrapped)

     _ (succeed
        (not (some :errors results2))
        (merge
         {:phase-errors true :phase :bootstrap
          :results (concat results1 results2)}
         (when-let [e (some #(some (comp :cause :error) (:errors %)) results2)]
           {:exception e})))

     [results3 plan-state] (reduce*
                            (fn reducer [[result plan-state] phase]
                              (dofsm reduce-phases
                                [[r ps] (primitives/build-and-execute-phase
                                         targets plan-state environment
                                         (api/environment-execution-settings
                                          environment)
                                          targets phase)
                                 _ (succeed
                                    (not (some :errors r))
                                    (merge
                                     {:phase-errors true
                                      :phase phase
                                      :results (concat result r)}
                                     (when-let [e (some
                                                   #(some
                                                     (comp :cause :error)
                                                     (:errors %))
                                                   r)]
                                       {:exception e})))]
                                [(concat result r) ps]))
                            [[] plan-state]
                            (remove #{:settings :bootstrap} phases))]
    {:results (concat results results1 results2 results3)
     :targets targets
     :plan-state plan-state
     :new-nodes new-nodes
     :old-nodes old-nodes}))
