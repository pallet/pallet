(ns pallet.core.data-api
  (:require  [pallet.node :as node])
  (:use [pallet.compute :only [service-properties]]
        [pallet.compute.node-list :only [node-list-service]]
        [pallet.api :only [lift group-spec plan-fn]]
        [pallet.executors :only [action-plan-data]]))

(defn service-map-from-compute [compute]
  (service-properties compute))

(defn nodes [compute]
  (for [node (pallet.compute/nodes compute)]
    {:ssh-port (node/ssh-port node)
     :primary-ip (node/primary-ip node)
     :private-ip (node/private-ip node)
     :is-64bit? (node/is-64bit? node)
     :group-name (node/group-name node)
     :hostname (node/hostname node)
     :os-family (node/os-family node)
     :os-version (node/os-family node)
     :running? (node/running? node)
     :terminated? (node/terminated? node)
     :id (node/id node)}))

(defn- mock-exec-plan
  "Creates mock provider with a mock node, and a mock group, and then lifts
 the plan funcion `pfn` on such group. "
  [executor pfn node & {:keys [settings-phase ]}]
  (let [compute (node-list-service [node])
        group-name (second node)
        os-family (nth node 3)]
    (let [group (merge (group-spec group-name :node {:os-family os-family})
                       (when settings-phase
                         {:phases {:settings (plan-fn (settings-phase))}}))]
      (lift [group]
            :phase pfn
            :environment
            {:algorithms
             {:executor executor}}
            :compute compute))))


(defn explain-plan [pfn node & {:keys [settings-phase]}]
  ;; build a node list with a node with the characteristics above
  (let [op (mock-exec-plan action-plan-data pfn node
                           :settings-phase settings-phase)]
    (mapcat :result (:results op))))
