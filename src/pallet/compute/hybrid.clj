(ns pallet.compute.hybrid
  "Hybrid provider service implementation."
  (:require
   [clojure.core.async :as async :refer [chan]]
   [clojure.tools.logging :as logging]
   [pallet.compute :as compute]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.protocols :as impl :refer [node-tag]]
   [pallet.core.node :as node]
   [pallet.core.protocols :as core-impl]
   [pallet.environment]
   [pallet.utils :refer [combine-exceptions]]
   [pallet.utils.async :refer [go-try]]))

(defn supported-providers []
  [:hybrid])

(defn- services
  "Return the service objects from the service map"
  [service-map]
  (vals service-map))


(defn forward-op
  "Forward an async op to the nodes' compute-service."
  [compute nodes ch f]
  (go-try ch
    (let [c (chan)
          compute-nodes (group-by node/compute-service nodes)]
      (for [[compute nodes] compute-nodes]
        (f compute nodes c))
      (let [[res es] (<! (async/reduce
                         (fn [[res es] [result e]]
                           [(concat res result) (conj es e)])
                         [[] []]
                         (async/take (count nodes) c)))]
        (>! ch [res (combine-exceptions es)])))))


(deftype HybridService
    [service-map dispatch environment]

  pallet.core.protocols.Closeable
  (close [compute] (mapcat pallet.compute/close (services service-map)))

  pallet.compute.protocols/ComputeService
  (nodes [compute ch]
    (go-try ch
      (>! ch [(mapcat pallet.compute/nodes (services service-map))])))


  pallet.compute.protocols/ComputeServiceNodeCreateDestroy
  (images [compute ch]
    (go-try ch
      (>! ch [(mapcat pallet.compute/images (services service-map))])))


  (create-nodes [compute node-spec user node-count options ch]
    ;; (pallet.compute/run-nodes
    ;;  (dispatch service-map group-spec)
    ;;  group-spec node-count user init-script options)
    )

  (destroy-nodes [compute nodes ch]
    (forward-op compute nodes ch compute/destroy-nodes))

  pallet.compute.protocols/ComputeServiceNodeStop
  (stop-nodes [compute nodes ch]
    (forward-op compute nodes ch compute/stop-nodes))

  (restart-nodes
    [compute nodes ch]
    (forward-op compute nodes ch compute/restart-nodes))

  pallet.compute.protocols/ComputeServiceNodeSuspend
  (suspend-nodes [compute nodes ch]
    (forward-op compute nodes ch compute/suspend-nodes))

  (resume-nodes [compute nodes ch]
    (forward-op compute nodes ch compute/resume-nodes))


  pallet.compute.protocols/ComputeServiceProperties
  (service-properties [compute]
    (assoc (bean compute) :provider :hybrid))

  ;; not implemented
  ;; pallet.compute.protocols/ComputeServiceNodeBaseName


  pallet.compute.protocols.NodeTagReader
  (node-tag [compute node tag-name]
    (impl/node-tag (node/compute-service node) node tag-name))
  (node-tag [compute node tag-name default-value]
    (impl/node-tag (node/compute-service node) node tag-name default-value))
  (node-tags [compute node]
    (impl/node-tags (node/compute-service node) node))
  pallet.compute.protocols.NodeTagWriter
  (tag-node! [compute node tag-name value]
    (impl/tag-node! (node/compute-service node) node tag-name value))
  (node-taggable? [compute node]
    (impl/node-taggable? (node/compute-service node) node))

  pallet.environment.protocols.Environment
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

;; TODO - fix this
;; (defn compute-provider-from-definition [definition]
;;   (if (map? definition)
;;     (configure/compute-service-from-map definition)
;;     definition))

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
          nil
          ;; TODO
          ;; (zipmap (keys sub-services)
          ;;         (map compute-provider-from-definition (vals sub-services)))
          ;; (into {} (map #(vector % (configure/compute-service %)) sub-services))
          )]
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
