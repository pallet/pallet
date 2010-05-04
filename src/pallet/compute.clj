(ns pallet.compute
  "Additions to the jclouds compute interface"
  (:use
   [pallet.utils
    :only [*admin-user* remote-sudo remote-sudo-script resource-properties]])
  (:require [org.jclouds.compute :as jclouds])
  (:import
   org.jclouds.compute.domain.internal.NodeMetadataImpl
   org.jclouds.compute.util.ComputeUtils
   [org.jclouds.compute.domain NodeState NodeMetadata Image]
   org.jclouds.domain.Location))


;;; Meta
(defn supported-clouds []
  (ComputeUtils/getSupportedProviders))

;;; Node utilities
(defn make-node [tag & options]
  (let [options (apply hash-map options)]
    (NodeMetadataImpl.
     tag                                ; id
     tag                                ; name
     (options :location)
     (java.net.URI. tag)                ; uri
     (get options :user-metadata {})
     tag
     (options :image)
     (get options :state NodeState/RUNNING)
     (get options :public-ips [])
     (get options :private-ips [])
     (get options :extra {})
     (get options :credentials nil))))

(defn make-unmanaged-node
  "Make a node that is not created by pallet's node management.
   This can be used to manage configuration of any machine accessable over
   ssh, including virtual machines."
  [tag host-or-ip & options]
  (let [options (apply hash-map options)
        meta (dissoc options :location :user-metadata :state :public-ips
                     :private-ips :extra :credentials)]
    (NodeMetadataImpl.
     tag                                ; id
     tag                                ; name
     (options :location)
     (java.net.URI. tag)                ; uri
     (merge (get options :user-metadata {}) meta)
     tag
     (options :image)
     (get options :state NodeState/RUNNING)
     (conj (get options :public-ips [])
           (java.net.InetAddress/getByName host-or-ip))
     (get options :private-ips [])
     (get options :extra {})
     (get options :credentials nil))))


(defn compute-node? [object]
  (instance? NodeMetadata object))

(defn ssh-port
  "Extract the port from the node's userMetadata"
  [node]
  (let [md (into {} (.getUserMetadata node))
        port (md :ssh-port)]
    (if port (Integer. port))))

(defn primary-ip
  "Returns the first public IP for the node."
  [#^NodeMetadata node]
  (first (jclouds/public-ips node)))

(defn node-has-tag? [tag node]
  (= (name tag) (jclouds/node-tag node)))

(defn nodes-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (jclouds/tag %2))
             (conj (get %1 (keyword (jclouds/tag %2)) []) %2)) {} nodes))

(defn node-counts-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (jclouds/tag %2))
             (inc (get %1 (keyword (jclouds/tag %2)) 0))) {} nodes))

 ;;; Actions
(defn reboot
  "Reboot the specified nodes"
  ([nodes] (reboot nodes jclouds/*compute*))
  ([nodes compute]
     (dorun (map #(jclouds/reboot-node % compute) nodes))))

(defn boot-if-down
  "Boot the specified nodes, if they are not running."
  ([nodes] (boot-if-down nodes jclouds/*compute*))
  ([nodes compute]
     (map #(jclouds/reboot-node % compute)
          (filter jclouds/terminated? nodes))))

(defn shutdown-node
  "Shutdown a node."
  ([node] (shutdown-node node *admin-user* jclouds/*compute*))
  ([node user] (shutdown-node node user jclouds/*compute*))
  ([node user compute]
     (let [ip (primary-ip node)]
       (if ip
         (remote-sudo ip "shutdown -h 0" user)))))

(defn shutdown
  "Shutdown specified nodes"
  ([nodes] (shutdown nodes *admin-user* jclouds/*compute*))
  ([nodes user] (shutdown nodes user jclouds/*compute*))
  ([nodes user compute]
     (dorun (map #(shutdown-node % compute) nodes))))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))

(defn execute-script
  "Execute a script on a specified node. Also accepts an IP or hostname as a
node."
  ([script node] (execute-script script node *admin-user*))
  ([script node user & options]
     (apply remote-sudo-script (node-address node) script user options)))

(defn node-locations
  "Return locations of a node as a seq."
  [#^NodeMetadata node]
  (letfn [(loc [#^Location l]
               (when l (cons l (loc (.getParent l)))))]
    (loc (.getLocation node))))

(defn image-string
  [#^Image image]
  (let [name (.getName image)
        description (.getOsDescription image)]
    (format "%s %s %s %s"
            (.getOsFamily image)
            (.getArchitecture image)
            name
            (if (= name description) "" description))))

(defn location-string
  [#^Location location]
  (format "%s/%s" (.getScope location) (.getId location)))

(defmethod clojure.core/print-method Location
   [location writer]
   (.write writer (location-string location)))

(defmethod clojure.core/print-method NodeMetadata
   [node writer]
   (.write
    writer
    (format
     "%14s\t %s %s\n\t\t %s\n\t\t %s\n\t\t public: %s  private: %s"
     (jclouds/node-tag node)
     (apply str (interpose "." (map location-string (node-locations node))))
     (.getDescription (.getLocation node))
     (image-string (.getImage node))
     (.getState node)
     (apply
      str (interpose
           ", " (map #(.getHostAddress %) (.getPublicAddresses node))))
     (apply
      str (interpose
           ", " (map #(.getHostAddress %) (.getPrivateAddresses node)))))))
