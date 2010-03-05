(ns pallet.compute
  (:use org.jclouds.compute
        [pallet.utils :only [remote-sudo resource-properties]]))

;;; Meta
(defn supported-clouds []
  (map second
       (filter (comp not nil?)
               (map #(re-find #"(.*)\.contextbuilder" %)
                    (keys (resource-properties "compute.properties"))))))

;;; Node utilities
(defn primary-ip
  "Returns the first public IP for the node."
  [#^NodeMetadata node]
  (first (public-ips node)))

(defn node-has-tag? [tag node]
  (= (name tag) (node-tag node)))

(defn nodes-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

(defn node-counts-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (inc (get %1 (keyword (tag %2)) 0))) {} nodes))

 ;;; Actions
(defn reboot [compute nodes]
  (dorun (map (partial reboot-node compute) nodes)))

(defn boot-if-down [compute nodes]
  (map (partial reboot-node compute)
       (filter terminated? nodes)))

(defn shutdown-node
  "Shutdown a node."
  [compute user node]
  (let [ip (primary-ip node)]
    (if ip
      (remote-sudo ip "shutdown -h 0" user))))

(defn shutdown [compute nodes]
  (dorun (map #(shutdown-node compute %) nodes)))


