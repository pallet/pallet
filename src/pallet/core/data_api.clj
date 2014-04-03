(ns pallet.core.data-api
  (:require
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.compute :refer [service-properties]]
   [pallet.compute.node-list :refer [node-list]]
   [pallet.executors :refer [action-plan-data]]
   [pallet.node :as node :refer [node-map]]))

(defn service-map-from-compute [compute]
  (service-properties compute))

(defn nodes [compute]
  (for [node (pallet.compute/nodes compute)]
   (node-map node)))

(defn- mock-exec-plan
  "Creates mock provider with a mock node, and a mock group, and then lifts
 the plan function `pfn` on such group. "
  [executor pfn node & {:keys [settings-phase ]}]
  (let [compute (node-list {:node-list [node]})
        group-name (second node)
        os-family (nth node 3)]
    (let [group (merge (group-spec group-name :image {:os-family os-family})
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
    (taoensso.timbre/debugf "explain-plan %s" op)
    (mapcat :result (:results op))))

;; -------- SESSION -------------

(defn- sorted-distinct
  "remove duplicates in the list preserving the order in which they first appeared"
  [coll]
  (reduce (fn [acc v] (if (some #{v} acc) acc (conj acc v))) [] coll))

(defn phase-seq
  "Returns a sequence of the phases as they were invoked."
  [session-data]
  (seq (sorted-distinct (map :phase (:runs session-data)))))

(defn groups
  "For a session-data structure, it generates the list of groups affected"
  [session-data]
  (seq (distinct (map :group-name (:runs session-data) ))))

(defn run-summary
  "returns a summary of a run of a phase on a group (from the :results
  key in the session)"
  [r]
  (let [target (:target r)
        node (node-map (:node target))]
    (merge
     {:phase (:phase r)
      :group-name (:group-name target)
      :action-results (:result r)
      :node (assoc node :image (:image target))})))

(defn session-data
  "Given a session data structure, returns a serializable data
  structure with the results of the session"
  [{:keys [results new-nodes old-nodes] :as session}]
  (let [runs (map run-summary results)]
    {:destroyed-nodes (seq (map #(node-map (:node %)) old-nodes))
     :created-nodes (seq (map #(node-map (:node %)) new-nodes))
     :runs runs}))
