(ns pallet.core.api
  "Base level API for pallet"
  (:use
   [pallet.compute :only [nodes]]
   [pallet.session.action-plan
    :only [assoc-action-plan get-session-action-plan]]
   pallet.core.api-impl))

(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a map that contains all the nodes, nodes for each
  group, and groups for each node. Also the service environment."
  [compute-service groups]
  (map (partial node->server groups) (nodes compute-service)))

(defn build-action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`."
  [service-state group-state environment node phase-fn]
  (with-script-for-node node
    (let [session {:service-state service-state
                   :target {:node node}
                   :group-state group-state}
          [rv session] (plan-f session)
          [action-plan session] (action-plan/translate
                                 (:action-plan session) session)]
      (conj ((juxt :service-state :group-state :action-plan) session) rv))))

(defn execute-action-plan
  "Execute the `action-plan` on the `node`."
  [service-state group-state environment node action-plan]
  (with-script-for-node node
    (let [session {:service-state service-state
                   :target {:node node}
                   :group-state group-state}
          (action-plan/execute action-plan session executor execute-status-fn)]
      (conj ((juxt :service-state :group-state :action-plan) session) rv))))
