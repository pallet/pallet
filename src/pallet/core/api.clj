(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [clojure.algo.monads :only [domonad m-map state-m with-monad]]
   [pallet.action-plan :only [execute stop-execution-on-error translate]]
   [pallet.compute :only [nodes]]
   [pallet.core :only [default-executor]]
   [pallet.session.action-plan
    :only [assoc-action-plan get-session-action-plan]]
   [pallet.session.verify :only [add-session-verification-key]]
   pallet.core.api-impl))

(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a map that contains all the nodes, nodes for each
  group, and groups for each node.

  Also the service environment."
  [compute-service groups]
  (let [nodes (nodes compute-service)
         ]
    {:node->groups (into {} (map (node->groups groups) nodes))
     :group->nodes (into {} (map (group->nodes nodes) groups))}))

(defn build-action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups."
  [service-state environment plan-fn node]
  (fn build-action-plan [plan-state]
    (with-script-for-node node
      (let [session (add-session-verification-key
                     {:service-state service-state
                      :server {:node node}
                      :plan-state plan-state})
            [rv session] (plan-fn session)
            [action-plan session] (translate (:action-plan session) session)]
        [action-plan (:plan-state session)]))))

(defn build-action-plans-for-group
  "Build action plans for the specified `phase` on the given `group`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups."
  [service-state environment phase group]
  (if-let [plan-fn (-> group :phases phase)]
    (let [nodes (-> service-state :group->nodes (get group))]
      (logging/debugf "service-state %s" service-state)
      (logging/debugf "nodes %s" (vec nodes))
      (with-monad state-m
        (domonad
         [action-plans (m-map
                        (partial
                         build-action-plan service-state environment plan-fn)
                        nodes)]
         (zipmap nodes action-plans))))
    (fn [plan-state] [nil plan-state])))

(defn build-action-plans
  "Build action plans for the specified `phase` on the given `groups`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups."
  [service-state environment phase groups]
  (logging/debugf "groups %s" (vec (map :group-name groups)))
  (with-monad state-m
    (domonad
     [action-plans (m-map
                    (partial
                     build-action-plans-for-group
                     service-state environment phase)
                    groups)]
     (apply merge-with comp action-plans))))

(defn execute-action-plan
  "Execute the `action-plan` on the `node`."
  [service-state plan-state environment action-plan node]
  (with-script-for-node node
    (let [executor (get-in environment [:algorithms :executor] default-executor)
          execute-status-fn (get-in environment [:algorithms :execute-status-fn]
                                    #'stop-execution-on-error)
          session {:service-state service-state
                   :server {:node node}
                   :plan-state plan-state
                   :user pallet.utils/*admin-user*}
          [result session] (execute
                            action-plan session executor execute-status-fn)]
      (logging/debugf
       "execute-action-plan returning %s" [(:plan-state session) result])
      [(:plan-state session) result])))



;; (reduce
;;  (fn [{:keys plan-state action-plans} node]
;;    (let [[plan-state action-plan] (build-action-plan
;;                                    service-state plan-state environment
;;                                    node plan-fn)]
;;      {:plan-state plan-state
;;       :action-plans (conj action-plans action-plan)}))
;;  {:plan-state plan-state :action-plans []}
;;  (nodes-in-group service-state group))
