(ns pallet.compute.jclouds
  "jclouds compute service implementation."
  (:require
   [org.jclouds.compute :as jclouds]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.script :as script]
   [pallet.utils :as utils]
   [pallet.execute :as execute]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging])
  (:import
   [org.jclouds.compute.domain.internal NodeMetadataImpl ImageImpl HardwareImpl]
   org.jclouds.compute.util.ComputeServiceUtils
   org.jclouds.compute.ComputeService
   org.jclouds.compute.options.RunScriptOptions
   org.jclouds.compute.options.TemplateOptions
   [org.jclouds.compute.domain
    NodeState NodeMetadata Image OperatingSystem OsFamily Hardware Template]
   org.jclouds.domain.Location
   org.jclouds.io.Payload
   org.jclouds.scriptbuilder.domain.Statement
   com.google.common.base.Predicate))


;;; Meta
(defn supported-providers []
  (ComputeServiceUtils/getSupportedProviders))

;;;; Compute service
(defn default-jclouds-extensions
  "Default extensions"
  [provider]
  (concat
   (if (jvm/log4j?) [:log4j] [])
   (if (= (name provider) "stub")
     (try
       (require 'pallet.compute.jclouds-ssh-test)
       (when-let [f (ns-resolve
                     'pallet.compute.jclouds-ssh-test 'ssh-test-client)]
         [(f (ns-resolve 'pallet.compute.jclouds-ssh-test 'no-op-ssh-client))])
       (catch java.io.FileNotFoundException _))
     [:ssh])))

(def ^{:private true :doc "translate option names"}
  option-keys
  {:endpoint :jclouds.endpoint})

(defn option-key
  [provider key]
  (case key
    :endpoint (keyword (format (str provider ".endpoint")))
    (option-keys key key)))

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


(defn make-node [group-name & options]
  (let [options (apply hash-map options)]
    (NodeMetadataImpl.
     (options :provider-id (options :id group-name))
     (options :name group-name)                ; name
     (options :id group-name)                   ; id
     (options :location)
     (java.net.URI. group-name)                ; uri
     (options :user-metadata {})
     group-name
     (if-let [hardware (options :hardware)]
       (if (map? hardware) (make-hardware hardware) hardware)
       (make-hardware {}))
     (options :image-id)
     (if-let [os (options :operating-system)]
       (if (map? os) (make-operating-system os) os)
       (make-operating-system {}))
     (options :state NodeState/RUNNING)
     (options :login-port 22)
     (options :public-ips [])
     (options :private-ips [])
     (options :admin-password)
     (options :credentials nil))))

(defn make-unmanaged-node
  "Make a node that is not created by pallet's node management.
   This can be used to manage configuration of any machine accessable over
   ssh, including virtual machines."
  [group-name host-or-ip & options]
  (let [options (apply hash-map options)
        meta (dissoc options :location :user-metadata :state :login-port
                     :public-ips :private-ips :extra :admin-password
                     :credentials)]
    (NodeMetadataImpl.
     (options :provider-id (options :id group-name))
     (options :name group-name)
     (options :id (str group-name (rand-int 65000)))
     (options :location)
     (java.net.URI. group-name)                ; uri
     (merge (get options :user-metadata {}) meta)
     group-name
     (if-let [hardware (options :hardware)]
       (if (map? hardware) (make-hardware hardware) hardware)
       (make-hardware {}))
     (options :image-id)
     (if-let [os (options :operating-system)]
       (if (map? os) (make-operating-system os) os)
       (make-operating-system {}))
     (get options :state NodeState/RUNNING)
     (options :login-port 22)
     (conj (get options :public-ips []) host-or-ip)
     (options :private-ips [])
     (options :admin-password)
     (options :credentials nil))))


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
     (options :operating-system)
     (options :description "image description")
     (options :version "image version")
     (options :admin-password)
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
  (group-name [node] (jclouds/tag node))

  (os-family
    [node]
    (when-let [operating-system (.getOperatingSystem node)]
      (keyword (str (.getFamily operating-system)))))

  (os-version
    [node]
    (when-let [operating-system (.getOperatingSystem node)]
      (.getVersion operating-system)))

  (hostname [node] (.getName node))
  (id [node] (.getId node))
  (running? [node] (jclouds/running? node))
  (terminated? [node] (jclouds/terminated? node)))


(defn- build-node-template
  "Build the template for specified target node and compute context"
  [compute group public-key-path init-script]
  {:pre [(map? group) (:group-name group)]}
  (logging/info
   (str "building node template for " (:group-name group)))
  (when public-key-path
    (logging/info (str "  authorizing " public-key-path)))
  (when init-script
    (logging/debug (str "  init script\n" init-script)))
  (let [options (->> [:image :hardware :location :network :qos]
                     (select-keys group)
                     vals
                     (reduce merge))
        options (if (:default-os-family group)
                  (dissoc options :os-family) ; remove if we added in
                                              ; ensure-os-family
                  options)]
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

(deftype JcloudsService
    [^org.jclouds.compute.ComputeService compute environment]

  ;; implement jclouds ComputeService by forwarding
  org.jclouds.compute.ComputeService
  (getContext [_] (.getContext compute))
  (templateBuilder [_] (.templateBuilder compute))
  (templateOptions [_] (.templateOptions compute))
  (listHardwareProfiles [_] (.listHardwareProfiles compute))
  (listImages [_] (.listImages compute))
  (listNodes [_] (.listNodes compute))
  (listAssignableLocations [_] (.listAssignableLocations compute))
  ;; (createNodesInGroup [_ group count template]
  ;;                     (.createNodesInGroup compute group count template))
  ;; (createNodesInGroup [_ group count]
  ;;                     (.createNodesInGroup compute group count))
  (^java.util.Set runNodesWithTag
   [_ ^String group ^int count ^Template template]
   (.createNodesInGroup compute group count template))
  (^java.util.Set runNodesWithTag
   [_ ^String group ^int count ^TemplateOptions template-options]
   (.createNodesInGroup compute group count template-options))
  (^java.util.Set runNodesWithTag
   [_ ^String group ^int count]
   (.createNodesInGroup compute group count))
  (resumeNode [_ id] (.resumeNode compute id))
  (resumeNodesMatching [_ predicate] (.resumeNodesMatching compute predicate))
  (suspendNode [_ id] (.suspendNode compute id))
  (suspendNodesMatching [_ predicate] (.suspendNodesMatching compute predicate))
  (rebootNode [_ id] (.rebootNode compute id))
  (rebootNodesMatching [_ predicate] (.rebootNodesMatching compute predicate))
  (getNodeMetadata [_ id] (.getNodeMetadata compute id))
  (listNodesDetailsMatching [_ predicate]
                            (.listNodesDetailsMatching compute predicate))
  (^java.util.Map runScriptOnNodesMatching
   [_ ^Predicate predicate ^Payload script]
   (.runScriptOnNodesMatching compute predicate script))
  (^java.util.Map runScriptOnNodesMatching
   [_ ^Predicate predicate ^Payload script ^RunScriptOptions options]
   (.runScriptOnNodesMatching compute predicate script options))
  ;; (^java.util.Map runScriptOnNodesMatching
  ;;  [_ ^Predicate predicate ^String script]
  ;;  (.runScriptOnNodesMatching compute predicate script))
  ;; (^java.util.Map runScriptOnNodesMatching
  ;;  [_ ^Predicate predicate ^String script ^RunScriptOptions options]
  ;;  (.runScriptOnNodesMatching compute predicate script options))
  ;; (^java.util.Map runScriptOnNodesMatching
  ;;  [_ ^Predicate predicate ^Statement script]
  ;;  (.runScriptOnNodesMatching compute predicate script))
  ;; (^java.util.Map runScriptOnNodesMatching
  ;;  [_ ^Predicate predicate ^Statement script ^RunScriptOptions options]
  ;;  (.runScriptOnNodesMatching compute predicate script options))

  pallet.compute.ComputeService
  (nodes [_] (jclouds/nodes-with-details compute))

  (ensure-os-family
   [_ group]
   (if (-> group :image :os-family)
     group
     (let [template (jclouds/build-template compute (:image group))
           family (-> (.. template getImage getOperatingSystem getFamily)
                      str keyword)]
       (logging/info (format "Default OS is %s" (pr-str family)))
       (when (or (nil? family) (= family OsFamily/UNRECOGNIZED))
         (condition/raise
          :type :unable-to-determine-os-type
          :message (format
                    (str "jclouds was unable to determine the os-family "
                         "of the template %s")
                    (pr-str (:image group)))))
       (->
        group
        (assoc-in [:image :os-family] family)
        (assoc-in [:default-os-family] true)))))

  (run-nodes
   [_ group-spec node-count user init-script]
   (->>
    (jclouds/run-nodes
     (name (:group-name group-spec))
     node-count
     (build-node-template
      compute
      group-spec
      (:public-key-path user)
      init-script)
     compute)
    ;; The following is a workaround for terminated nodes.
    ;; See http://code.google.com/p/jclouds/issues/detail?id=501
    (filter compute/running?)))

  (reboot
   [_ nodes]
   (doseq [node nodes]
     (jclouds/reboot-node node compute)))

  (boot-if-down
   [_ nodes]
   (map #(jclouds/reboot-node % compute)
        (filter jclouds/terminated? nodes)))

  (shutdown-node
   [_ node user]
   (let [ip (compute/primary-ip node)]
     (if ip
       (execute/remote-sudo ip "shutdown -h 0" user))))

  (shutdown
   [self nodes user]
   (doseq [node nodes]
     (compute/shutdown-node self node user)))

  (destroy-nodes-in-group
    [_ group-name]
    (jclouds/destroy-nodes-with-tag (name group-name) compute))

  (destroy-node
   [_ node]
   (jclouds/destroy-node (compute/id node) compute))

  (close [_] (.. compute getContext close))

  pallet.environment.Environment
  (environment [_] environment))

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
    (jclouds/tag node)
    (apply str (interpose "." (map location-string (node-locations node))))
    (let [location (.getLocation node)]
      (when (and location
                 (not (= (.getDescription location) (.getId location))))
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

(defn local-session
  "Create a session map for localhost"
  []
  (let [node (make-localhost-node)]
    {:all-nodes [node]
     :server {:image [(get jvm-os-map (System/getProperty "os.name"))]
              :node node}}))

;; service factory implementation for jclouds
(defmethod implementation/service :default
  [provider {:keys [identity credential extensions endpoint environment]
             :or {extensions (default-jclouds-extensions provider)}
             :as options}]
  (logging/debug (format "extensions %s" (pr-str extensions)))
  (let [options (dissoc
                 options
                 :identity :credential :extensions :blobstore :environment)]
    (JcloudsService.
     (apply
      jclouds/compute-service
      (name provider) identity credential
      :extensions extensions
      (interleave
       (map #(option-key provider %) (keys options))
       (vals options)))
     environment)))
