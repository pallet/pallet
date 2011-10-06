(ns pallet.compute.hybrid
  "Hybrid provider service implementation."
  (:require
   [pallet.compute.implementation :as implementation]
   [pallet.configure :as configure]
   [clojure.tools.logging :as logging]))

(deftype HybridService
    [services dispatch environment]
  pallet.compute/ComputeService
  (nodes [compute]
    (mapcat pallet.compute/nodes services))
  (run-nodes [compute group-spec node-count user init-script]
    (pallet.compute/run-nodes
     (dispatch group-spec)
     group-spec node-count user init-script))
  (reboot [compute nodes]
    (doseq [node nodes]
      (pallet.compute/reboot (pallet.compute/service node) node)))
  (boot-if-down [compute nodes]
    (doseq [node nodes]
      (pallet.compute/boot-if-down (pallet.compute/service node) node)))
  (shutdown-node [compute node user]
    (pallet.compute/shutdown-node (pallet.compute/service node) node user))
  (shutdown [compute nodes user]
    (doseq [node nodes]
      (pallet.compute/shutdown-node (pallet.compute/service node) node user)))
  (ensure-os-family [compute group-spec]
    (pallet.compute/ensure-os-family (dispatch group-spec) group-spec))
  (destroy-nodes-in-group [compute group-name]
    (pallet.compute/destroy-nodes-in-group (dispatch group-name) group-name))
  (destroy-node [compute node]
    (pallet.compute/destroy-node (pallet.compute/service node) node))
  (images [compute] (mapcat pallet.compute/images services))
  (close [compute] (mapcat pallet.compute/close services)))

(defn group-dispatcher
  "Return a dispatch function based on a map from service to groups."
  [groups-for-services]
  (let [g->s (into {} (apply concat
                             (for [[service groups] groups-for-services]
                               (map #(vector % service) groups))))]
    (fn group-dispatch-fn
      [spec-or-name]
      (if (string? spec-or-name)
        (g->s (keyword spec-or-name))
        (g->s (:group-name spec-or-name))))))

;; service factory implementation for hybrid provider
(defmethod implementation/service :hybrid
  [provider {:keys [sub-services
                    groups-for-services
                    service-dispatcher
                    environment]
             :as options}]
  (HybridService.
   (map #(if (keyword? %) (configure/compute-service %) %) sub-services)
   (or service-dispatcher
       (and groups-for-services (group-dispatcher groups-for-services)))
   environment))
