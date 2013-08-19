(ns pallet.compute.hybrid
  "Hybrid provider service implementation."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.compute.implementation :as implementation]
   [pallet.configure :as configure]
   [pallet.node :as node]))

(defn supported-providers []
  ["hybrid"])

(defn- services
  "Return the service objects from the service map"
  [service-map]
  (vals service-map))

(deftype HybridService
    [service-map dispatch environment]
  pallet.compute/ComputeService
  (nodes [compute]
    (mapcat pallet.compute/nodes (services service-map)))
  (run-nodes [compute group-spec node-count user init-script options]
    (pallet.compute/run-nodes
     (dispatch service-map group-spec)
     group-spec node-count user init-script options))
  (reboot [compute nodes]
    (doseq [node nodes]
      (pallet.compute/reboot (node/compute-service node) node)))
  (boot-if-down [compute nodes]
    (doseq [node nodes]
      (pallet.compute/boot-if-down (node/compute-service node) node)))
  (shutdown-node [compute node user]
    (pallet.compute/shutdown-node (node/compute-service node) node user))
  (shutdown [compute nodes user]
    (doseq [node nodes]
      (pallet.compute/shutdown-node (node/compute-service node) node user)))
  (ensure-os-family [compute group-spec]
    (pallet.compute/ensure-os-family
     (dispatch service-map group-spec)
     group-spec))
  (destroy-nodes-in-group [compute group-name]
    (pallet.compute/destroy-nodes-in-group
     (dispatch service-map (name group-name))
     group-name))
  (destroy-node [compute node]
    (pallet.compute/destroy-node (node/compute-service node) node))
  (images [compute] (mapcat pallet.compute/images (services service-map)))
  (close [compute] (mapcat pallet.compute/close (services service-map)))
  pallet.environment.Environment
  (environment [_]
    (apply merge (conj (map pallet.environment/environment
                                     (vals service-map))
                                environment))))

(defn ensure-service-dispatch
  [f]
  (fn [service-map group-spec]
    (service-map
     (or
      (f service-map group-spec)
      (throw
       (RuntimeException.
        (str "No dispatch for group " group-spec)))))))

(defn group-dispatcher
  "Return a dispatch function based on a map from service to groups."
  [groups-for-services]
  (let [g->s (into {} (apply concat
                             (for [[service groups] groups-for-services]
                               (map #(vector % service) groups))))]
    (logging/infof "Hybrid dispatch function: %s" g->s)
    (fn group-dispatch-fn
      [spec-or-name]
      (if (string? spec-or-name)
        (g->s (keyword spec-or-name))
        (g->s (:group-name spec-or-name))))))

;; service factory implementation for hybrid provider

(defn compute-provider-from-definition [definition]
  (if (map? definition)
    (configure/compute-service-from-map definition)
    definition))

;;; sub-services is either a sequence of service keywords, or a map
;;; from service name to a service configuration map.
(defmethod implementation/service :hybrid
  [provider {:keys [sub-services
                    groups-for-services
                    service-dispatcher
                    environment]
             :as options}]
  (let [service-map
        (if (map? sub-services)
          (zipmap (keys sub-services)
                  (map compute-provider-from-definition (vals sub-services)))
          (into {} (map #(vector % (configure/compute-service %)) sub-services)))]
    (logging/infof "sub-services for hybrid provider: %s" service-map)
    (logging/debugf "groups-for-services map: %s" groups-for-services)
    (HybridService.
     service-map
     (or (and
          service-dispatcher
          (ensure-service-dispatch service-dispatcher))
         (and
          groups-for-services
          (ensure-service-dispatch (group-dispatcher groups-for-services))))
     environment)))
