(ns pallet.compute
  (:use org.jclouds.compute
        [pallet.utils :only [*admin-user* remote-sudo remote-sudo-script
                             resource-properties]]))

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
(defn reboot
  "Reboot the specified nodes"
  ([nodes] (reboot nodes *compute*))
  ([nodes compute]
     (dorun (map #(reboot-node % compute) nodes))))

(defn boot-if-down
  "Boot the specified nodes, if they are not running."
  ([nodes] (boot-if-down nodes *compute*))
  ([nodes compute]
     (map #(reboot-node % compute)
          (filter terminated? nodes))))

(defn shutdown-node
  "Shutdown a node."
  ([node] (shutdown-node node *admin-user* *compute*))
  ([node user] (shutdown-node node user *compute*))
  ([node user compute]
     (let [ip (primary-ip node)]
       (if ip
         (remote-sudo ip "shutdown -h 0" user)))))

(defn shutdown
  "Shutdown specified nodes"
  ([nodes] (shutdown nodes *admin-user* *compute*))
  ([nodes user] (shutdown nodes user *compute*))
  ([nodes user compute]
     (dorun (map #(shutdown-node % compute) nodes))))

(defn execute-script
  "Execute a script on a specified node."
  ([script node] (execute-script script node *admin-user*))
  ([script node user]
     (remote-sudo-script (primary-ip node) script user)))

