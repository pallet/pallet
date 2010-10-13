(ns pallet.compute
  "Additions to the jclouds compute interface"
  (:require
   [org.jclouds.compute :as jclouds]
   [pallet.utils :as utils]
   [pallet.maven :as maven]
   [pallet.execute :as execute])
  (:import
   [org.jclouds.compute.domain.internal NodeMetadataImpl ImageImpl HardwareImpl]
   org.jclouds.compute.util.ComputeServiceUtils
   [org.jclouds.compute.domain
    NodeState NodeMetadata Image OperatingSystem OsFamily Hardware]
   org.jclouds.domain.Location))


;;; Meta
(defn supported-clouds []
  (ComputeServiceUtils/getSupportedProviders))

;;;; Compute service
(defn log4j?
  "Predicate to test for log4j on the classpath."
  []
  (try
    (import org.apache.log4j.Logger)
    true
    (catch java.lang.ClassNotFoundException _
      false)))

(defn default-jclouds-extensions
  "Default extensions"
  []
  (if (log4j?)
    [:ssh :log4j]
    [:ssh]))

(defn compute-service-from-settings
  "Create a jclouds compute service from maven ~/.m2/settings.xml.  If
   extensions are listed they are used, otherwise :log4j and
   :ssh are automatically added."
  [& extensions]
  (apply jclouds/compute-service
         (concat (maven/credentials)
                 (or (seq extensions) (default-jclouds-extensions)))))

;;; Node utilities
(defn make-operating-system
  [{:keys [family name version arch description is-64bit]
    :or {family OsFamily/UBUNTU
         name "Ubuntu"
         version "Some version"
         arch "Some arch"
         description "Desc"
         is-64bit true}}]
  (OperatingSystem. family name version arch description is-64bit))

(def jvm-os-map
  {"Mac OS X" :os-x})

(def jvm-os-family-map
  {"AIX" OsFamily/AIX
   "ARCH" OsFamily/ARCH
   "Mac OS" OsFamily/DARWIN
   "Mac OS X" OsFamily/DARWIN
   "FreeBSD" OsFamily/FREEBSD
   "HP UX" OsFamily/HPUX
   "Linux"   OsFamily/UBUNTU ;; guess for now
   "Solaris" OsFamily/SOLARIS
   "Windows 2000" OsFamily/WINDOWS
   "Windows 7" OsFamily/WINDOWS
   "Windows 95" OsFamily/WINDOWS
   "Windows 98" OsFamily/WINDOWS
   "Windows NT" OsFamily/WINDOWS
   "Windows Vista" OsFamily/WINDOWS
   "Windows XP" OsFamily/WINDOWS})

(defn local-operating-system
  "Create an OperatingSystem object for the local host"
  []
  (let [os-name (System/getProperty "os.name")]
    (make-operating-system
     {:family (jvm-os-family-map os-name OsFamily/UNRECOGNIZED)
      :name os-name
      :description os-name
      :version (System/getProperty "os.version")
      :arch (System/getProperty "os.arch")
      :is-64bit (= "64" (System/getProperty "sun.arch.data.model"))})))

(defn make-hardware
  [{:keys [provider-id name id location uri user-metadata processors ram
           volumes supports-image]
    :or {provider-id "provider-hardware-id"
         name "Some Hardware"
         id "Some id"
         user-metadata {}
         processors []
         ram 512
         volumes []
         supports-image (fn [&] true)}}]
  (HardwareImpl.
   provider-id name id location uri user-metadata processors ram volumes
   (reify com.google.common.base.Predicate
     (apply [_ i] (supports-image i))
     (equals [_ i] (= supports-image i)))))

(defn local-hardware
  "Create an Hardware object for the local host"
  []
  (let [os-name (System/getProperty "os.name")]
    (make-hardware {})))


(defn make-node [tag & options]
  (let [options (apply hash-map options)]
    (NodeMetadataImpl.
     (options :provider-id (options :id tag))
     (options :name tag)                ; name
     (options :id tag)                   ; id
     (options :location)
     (java.net.URI. tag)                ; uri
     (options :user-metadata {})
     tag
     (if-let [hardware (options :hardware)]
       (if (map? hardware) (make-hardware hardware) hardware)
       (make-hardware {}))
     (options :image-id)
     (if-let [os (options :operating-system)]
       (if (map? os) (make-operating-system os) os)
       (make-operating-system {}))
     (options :state NodeState/RUNNING)
     (options :public-ips [])
     (options :private-ips [])
     (options :credentials nil))))

(defn make-unmanaged-node
  "Make a node that is not created by pallet's node management.
   This can be used to manage configuration of any machine accessable over
   ssh, including virtual machines."
  [tag host-or-ip & options]
  (let [options (apply hash-map options)
        meta (dissoc options :location :user-metadata :state :public-ips
                     :private-ips :extra :credentials)]
    (NodeMetadataImpl.
     (options :provider-id (options :id tag))
     (options :name tag)
     (options :id (str tag (rand-int 65000)))
     (options :location)
     (java.net.URI. tag)                ; uri
     (merge (get options :user-metadata {}) meta)
     tag
     (if-let [hardware (options :hardware)]
       (if (map? hardware) (make-hardware hardware) hardware)
       (make-hardware {}))
     (options :image-id)
     (if-let [os (options :operating-system)]
       (if (map? os) (make-operating-system os) os)
       (make-operating-system {}))
     (get options :state NodeState/RUNNING)
     (conj (get options :public-ips []) host-or-ip)
     (get options :private-ips [])
     (get options :credentials nil))))


(defn make-image
  [id & options]
  (let [options (apply hash-map options)
        meta (dissoc options :name :location :uri :user-metadata
                     :version :operating-system :default-credentials
                     :description)]
    (ImageImpl.
     id ; providerId
     (options :name)
     id
     (options :location)
     (options :uri)
     (merge (get options :user-metadata {}) meta)
     (get options :operating-system)
     (get options :description "image description")
     (get options :version "image version")
     (options :default-credentials))))

(defn compute-node? [object]
  (instance? NodeMetadata object))

(defn ssh-port
  "Extract the port from the node's userMetadata"
  [node]
  (let [md (into {} (.getUserMetadata node))
        port (:ssh-port md)]
    (if port (Integer. port))))

(defn primary-ip
  "Returns the first public IP for the node."
  [#^NodeMetadata node]
  (first (jclouds/public-ips node)))

(defn private-ip
  "Returns the first private IP for the node."
  [#^NodeMetadata node]
  (first (jclouds/private-ips node)))

(defn is-64bit?
  [#^NodeMetadata node]
  (.. node getOperatingSystem is64Bit))

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
  ([node] (shutdown-node node utils/*admin-user* jclouds/*compute*))
  ([node user] (shutdown-node node user jclouds/*compute*))
  ([node user compute]
     (let [ip (primary-ip node)]
       (if ip
         (execute/remote-sudo ip "shutdown -h 0" user)))))

(defn shutdown
  "Shutdown specified nodes"
  ([nodes] (shutdown nodes utils/*admin-user* jclouds/*compute*))
  ([nodes user] (shutdown nodes user jclouds/*compute*))
  ([nodes user compute]
     (dorun (map #(shutdown-node % compute) nodes))))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))

(defn node-os-family
  "Return a nodes os-family, or nil if not available."
  [#^NodeMetadata node]
  (when-let [operating-system (.getOperatingSystem node)]
    (keyword (str (.getFamily operating-system)))))

(defn node-locations
  "Return locations of a node as a seq."
  [#^NodeMetadata node]
  (letfn [(loc [#^Location l]
               (when l (cons l (loc (.getParent l)))))]
    (loc (.getLocation node))))

(defn image-string
  [#^Image image]
  (when image
    (let [name (.getName image)
          description (.getDescription image)]
      (format "%s %s %s %s"
              (.getFamily (.getOperatingSystem image))
              (.getArch (.getOperatingSystem image))
              name
              (if (= name description) "" description)))))

(defn os-string
  [#^OperatingSystem os]
  (when os
    (let [name (.getName os)
          description (.getDescription os)]
      (format "%s %s %s %s"
              (.getFamily os)
              (.getArch os)
              name
              (if (= name description) "" description)))))

(defn location-string
  [#^Location location]
  (when location
    (format "%s/%s" (.getScope location) (.getId location))))

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
    (let [location (.getLocation node)]
      (when (and location (not (= (.getDescription location) (.getId location))))
        (.getDescription location)))
    (os-string (.getOperatingSystem node))
    (.getState node)
    (apply
     str (interpose ", " (.getPublicAddresses node)))
    (apply
     str (interpose ", " (.getPrivateAddresses node))))))

(def jvm-os-map
     { "Mac OS X" :os-x })

(defn make-localhost-node
  "Make a node representing the local host"
  []
  (make-node "localhost"
             :public-ips ["127.0.0.1"]
             :operating-system (local-operating-system)))

(defn local-request
  "Create a request map for localhost"
  []
  (let [node (make-localhost-node)]
    {:target-node node
     :all-nodes [node]
     :target-nodes [node]
     :node-type {:image [(get jvm-os-map (System/getProperty "os.name"))]}}))

(defn hostname
  "TODO make this work on ec2"
  [node]
  (or (.getName node)))
