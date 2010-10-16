(ns pallet.compute.jclouds
  "jclouds compute service implementation."
  (:require
   [org.jclouds.compute :as jclouds]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.script :as script]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [pallet.execute :as execute]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging])
  (:import
   [org.jclouds.compute.domain.internal NodeMetadataImpl ImageImpl HardwareImpl]
   org.jclouds.compute.util.ComputeServiceUtils
   org.jclouds.compute.ComputeService
   [org.jclouds.compute.domain
    NodeState NodeMetadata Image OperatingSystem OsFamily Hardware]
   org.jclouds.domain.Location))


;;; Meta
(defn supported-providers []
  (ComputeServiceUtils/getSupportedProviders))

;;;; Compute service
(defn default-jclouds-extensions
  "Default extensions"
  []
  (if (jvm/log4j?)
    [:ssh :log4j]
    [:ssh]))

(defn compute-service-from-settings
  "Create a jclouds compute service from propery settings.  If
   extensions are listed they are used, otherwise :log4j and
   :ssh are automatically added."
  [credentials & extensions]
  (apply jclouds/compute-service
         (concat credentials
                 (or (seq extensions) (default-jclouds-extensions)))))

(defmethod implementation/service :default
  [provider {:keys [identity credential extensions]
             :or {extensions (default-jclouds-extensions)}}]
  (jclouds/compute-service
   provider identity credential :extensions extensions))


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
     {:family (or (jvm-os-family-map (jvm/os-name)) OsFamily/UNRECOGNIZED)
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


(extend-type org.jclouds.compute.domain.NodeMetadata
  pallet.compute/Node

  (ssh-port
    [node]
    (let [md (into {} (.getUserMetadata node))
          port (:ssh-port md)]
      (if port (Integer. port))))

  (primary-ip [node] (first (jclouds/public-ips node)))
  (private-ip [node] (first (jclouds/private-ips node)))
  (is-64bit? [node] (.. node getOperatingSystem is64Bit))
  (tag [node] (jclouds/tag node))

  (os-family
    [node]
    (when-let [operating-system (.getOperatingSystem node)]
      (keyword (str (.getFamily operating-system)))))

  (hostname [node] (.getName node))
  (id [node] (.getId node))
  (running? [node] (jclouds/running? node))
  (terminated? [node] (jclouds/terminated? node)))



(defn build-node-template
  "Build the template for specified target node and compute context"
  [compute public-key-path request init-script]
  {:pre [(map? (:node-type request))]}
  (logging/info
   (str "building node template for " (-> request :node-type :tag)))
  (when public-key-path
    (logging/info (str "  authorizing " public-key-path)))
  (when init-script
    (logging/debug (str "  init script\n" init-script)))
  (let [options (-> request :node-type :image)]
    (logging/info (str "  options " options))
    (let [options (if (and public-key-path
                           (not (:authorize-public-key options)))
                    (assoc options
                      :authorize-public-key (slurp public-key-path))
                    options)
          options (if (not (:run-script options))
                    (if init-script
                      (assoc options :run-script (.getBytes init-script))
                      options)
                    options)]
      (jclouds/build-template compute options))))

(extend-type org.jclouds.compute.ComputeService
  pallet.compute/ComputeService

  (nodes [compute] (jclouds/nodes-with-details compute))

  (ensure-os-family
   [compute request]
   (if (-> request :node-type :image :os-family)
     request
     (let [template (jclouds/build-template
                     (:compute request)
                     (-> request :node-type :image))
           family (-> (.. template getImage getOperatingSystem getFamily)
                      str keyword)]
       (logging/info (format "Default OS is %s" (pr-str family)))
       (assoc-in request [:node-type :image :os-family] family))))

  (run-nodes
   [compute node-type node-count request init-script]
   (jclouds/run-nodes
    (name (node-type :tag))
    node-count
    (build-node-template
     (:compute request)
     (-> request :user :public-key-path)
     request
     init-script)
    compute))

  (reboot
   [compute nodes]
   (doseq [node nodes]
     (jclouds/reboot-node node compute)))

  (boot-if-down
   [compute nodes]
   (map #(jclouds/reboot-node % compute)
        (filter jclouds/terminated? nodes)))

  (shutdown-node
   [compute node user]
   (let [ip (compute/primary-ip node)]
     (if ip
       (execute/remote-sudo ip "shutdown -h 0" user))))

  (shutdown
   [compute nodes user]
   (doseq [node nodes]
     (compute/shutdown-node compute node user)))

  (destroy-nodes-with-tag
    [compute tag-name]
    (jclouds/destroy-nodes-with-tag (name tag-name) compute))

  (compute/destroy-node
   [compute node]
   (jclouds/destroy-node (compute/id node) compute)))

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
