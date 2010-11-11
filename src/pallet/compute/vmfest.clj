(ns pallet.compute.vmfest
  "A vmfest provider"
  (:require
   [vmfest.virtualbox.virtualbox :as virtualbox]
   [vmfest.virtualbox.session :as session]
   [vmfest.virtualbox.machine :as machine]
   [vmfest.virtualbox.model :as model]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.core :as core]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging])
  (:import
   com.sun.xml.ws.commons.virtualbox_3_2.IMedium
   com.sun.xml.ws.commons.virtualbox_3_2.IMachine
   com.sun.xml.ws.commons.virtualbox_3_2.INetworkAdapter))

(defn supported-providers []
  ["virtualbox"])

(def os-family-name
  {:ubuntu "Ubuntu"
   ;:rhel "RedHat"
   :rhel "RedHat_64"})

(def os-family-from-name
  (zipmap (vals os-family-name) (keys os-family-name)))

(extend-type vmfest.virtualbox.model.Machine
  pallet.compute/Node
  (ssh-port [node] 22)
  (primary-ip
   [node]
   (session/with-remote-session node [session machine]
     (.getGuestPropertyValue
       machine
       "/VirtualBox/GuestInfo/Net/0/V4/IP")))
  (private-ip [node] "")
  (is-64bit?
   [node]
   (session/with-no-session node [machine]
     (re-find #"64 bit" (.getOSTypeId machine))))
  (tag
   [node]
   (session/with-no-session node [machine]
     (.getExtraData machine "/pallet/tag")))
  (hostname
   [node]
   (session/with-no-session node [machine]
     (.getName machine)))
  (os-family
   [node]
   (session/with-no-session node [machine]
     (let [os-name (.getOSTypeId machine)]
       (os-family-from-name os-name os-name))))
  (running? [node] true)
  (terminated? [node] false)
  (id [node] ""))


;; (defn connection
;;   [host port identity credential]
;;   (let [manager (#'vmfest.virtualbox.virtualbox/create-session-manager host port)
;;         virtual-box (#'vmfest.virtualbox.virtualbox/create-vbox
;;                      manager identity credential)]
;;     [manager virtual-box]))

(defn find-matching-os [node-type os-types]
  (let [os-family (or (-> node-type :image :os-family) :ubuntu)
        os-type-id (os-family-name os-family)
        os-type (first (filter #(= os-type-id (.getId %)) os-types))]
    (if os-type
      (.getId os-type)
      (throw (Exception. "Can not find a matching os type")))))

(defn find-matching-machines [os-type-id machines]
  (filter #(= os-type-id (.getOSTypeId %)) machines))

(defn copy-machine-properties
  "Copy the template machine's devices to the new machine."
  [template-machine machine system-properties]
  (.setOSTypeId machine (.getOSTypeId template-machine))
  (.setCPUCount machine (.getCPUCount template-machine))
  (.setCPUHotPlugEnabled machine (.getCPUHotPlugEnabled template-machine))
  (.setMemorySize machine (.getMemorySize template-machine))
  ;;(.setMemoryBalloonSize machine (.getMemoryBalloonSize template-machine))
  ;; 64bit host
  ;;(.setPageFusionEnabled machine (.getPageFusionEnabled template-machine))
  ;; 64bit host
  (.setVRAMSize machine (.getVRAMSize template-machine))
  (.setAccelerate3DEnabled machine (.getAccelerate3DEnabled template-machine))
  (.setAccelerate2DVideoEnabled machine (.getAccelerate2DVideoEnabled
                                         template-machine))
  (.setMonitorCount machine (.getMonitorCount template-machine))
  (.setFirmwareType machine (.getFirmwareType template-machine))
  (.setPointingHidType machine (.getPointingHidType template-machine))
  (.setKeyboardHidType  machine (.getKeyboardHidType  template-machine))
  (.setHpetEnabled machine (.getHpetEnabled template-machine))
  (.setClipboardMode machine (.getClipboardMode template-machine))
  (.setGuestPropertyNotificationPatterns
   machine (.getGuestPropertyNotificationPatterns template-machine))
  (.setRTCUseUTC machine (.getRTCUseUTC template-machine))
  (.setIoCacheEnabled machine (.getIoCacheEnabled template-machine))
  (.setIoCacheSize machine (.getIoCacheSize template-machine))
  (.setIoBandwidthMax machine (.getIoBandwidthMax template-machine))
  (doseq [i (range (long 1) (.getMaxBootPosition system-properties))]
    (when-let [dt (.getBootOrder template-machine i)]
      (.setBootOrder machine i dt)))
  (let [tm-audio (.getAudioAdapter template-machine)
        audio (.getAudioAdapter machine)]
    (.setEnabled audio (.getEnabled tm-audio))
    (when (.getEnabled tm-audio)
      (.setAudioController audio (.getAudioController tm-audio))
      (.setAudioDriver audio (.getAudioDriver tm-audio)))))

(defn copy-storage-controllers
  "Copy the template machine's devices to the new machine."
  [template-machine machine]
  (doseq [controller (.getStorageControllers template-machine)]
    (let [sc (.addStorageController
              machine (.getName controller) (.getBus controller))]
      (.setControllerType sc (.getControllerType controller))
      (.setInstance sc (.getInstance controller))
      (.setPortCount sc (.getPortCount controller))
      (.setUseHostIOCache sc (.getUseHostIOCache controller))
      (when (= (.getControllerType sc)
               org.virtualbox_3_2.StorageControllerType/INTEL_AHCI)
        (.setIDEEmulationPort sc (.getIDEEmulationPort controller))))))


(defn clone-medium [virtual-box ^IMedium base-medium filename]
  (when base-medium
    (let [location-file (java.io.File. filename)]
      (when (.exists location-file)
        (.delete location-file)))
    (let [medium (.createHardDisk
                  virtual-box (.getFormat base-medium) filename)]
      (.lockRead base-medium)
      (try
        (.. (.createDiffStorage
             base-medium medium org.virtualbox_3_2.MediumVariant/DIFF)
            (waitForCompletion 10000))
        (when (= (.getId medium) (java.util.UUID. 0 0))
          (condition/raise
           :type :media-clone-failure
           :message "Media cloning failed"))
        (finally
         (.unlockRead base-medium)))
      medium)))

(defn copy-medium-attachments
  "Copy the template machine's devices to the new machine."
  [virtual-box template-machine vmfest-machine filename-fn]
  {:pre [virtual-box template-machine vmfest-machine filename-fn]}
  (doseq [attachment (.getMediumAttachments template-machine)]
    (let [medium (.getMedium attachment)
          medium (if (not= org.virtualbox_3_2.DeviceType/HARD_DISK
                           (.getType attachment))
                   medium
                   (clone-medium
                    virtual-box
                    (.getParent medium)
                    (filename-fn
                     (format
                      "%s-%s-%s"
                      (.getController attachment)
                      (.getPort attachment)
                      (.getDevice attachment)))))]
      (when medium
        (session/with-direct-session vmfest-machine [session machine]
          (.attachDevice
           machine
           (.getController attachment)
           (.getPort attachment)
           (.getDevice attachment)
           (.getType attachment)
           (.getId medium))
          (.saveSettings machine))))))

(defn nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn copy-network-controllers
  "Copy the template machine's devices to the new machine."
  [^IMachine template-machine ^IMachine machine system-properties]
  (doseq [i (range (.getNetworkAdapterCount system-properties))]
    (let [^INetworkAdapter na (.getNetworkAdapter machine (long i))
          ^INetworkAdapter t-na (.getNetworkAdapter template-machine (long i))]
      (.setAdapterType na (.getAdapterType t-na))
      (.setEnabled na (.getEnabled t-na))
      (when-let [host-interface (nil-if-blank (.getHostInterface t-na))]
        (.attachToHostOnlyInterface na)
        (.setHostInterface na (.getHostInterface t-na)))
      (when-let [internal-network (nil-if-blank (.getInternalNetwork t-na))]
        (.attachToInternalNetwork na)
        (.setInternalNetwork na internal-network))
      (when-let [nat-network (nil-if-blank (.getNATNetwork t-na))]
        (.attachToNAT na)
        (.setNATNetwork na nat-network))
      ;; (when-let [vde-network (nil-if-blank (.getVDENetwork t-na))]
      ;;   (.attachToVDE na)
      ;;   (.setVDENetwork na vde-network))
      (.setCableConnected na (.getCableConnected t-na))
      (.setLineSpeed na (.getLineSpeed t-na))
      (.setTraceEnabled na (.getTraceEnabled t-na))
      (.setTraceFile na (.getTraceFile t-na))
      (.setBootPriority na (.getBootPriority t-na)))))

(defn copy-machine
  "Copy the template machine's devices to the new machine."
  [template-machine machine system-properties]
  (copy-machine-properties template-machine machine system-properties)
  (copy-storage-controllers template-machine machine)
  (copy-network-controllers template-machine machine system-properties))

(defn wait-for-ip
  "Wait for the machines IP to become available."
  [machine]
  (session/with-remote-session machine [session machine]
    (loop []
      (let [ip (.getGuestPropertyValue
                machine "/VirtualBox/GuestInfo/Net/0/V4/IP")]
        (when (string/blank? ip)
          (Thread/sleep 500)
          (recur))))))

(defn remove-media [session]
  (let [machine (.getMachine session)
        attachments (.getMediumAttachments machine)]
    (doseq [medium-attachment attachments
            :let [^IMedium medium (.getMedium medium-attachment)
                  state (.getState (.getMedium medium-attachment))]]
      (.detachDevice
       machine
       (.getController medium-attachment)
       (.getPort medium-attachment)
       (.getDevice medium-attachment))
      (.saveSettings machine)
      (let [location (.getLocation medium)]
        (when (.contains location (str "/" (.getName machine) "."))
          (.deleteStorage medium))))))

(defn machine-name
  "Generate a machine name"
  [tag n]
  (format "%s-%s" tag n))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))

(extend-type vmfest.virtualbox.model.Server
  ;; VirtualBoxService
  ;; (os-families
  ;;  [compute]
  ;;  (let [[manager virtual-box] (connection host port identity credential)]
  ;;    (try
  ;;      (.getGuestOSTypes virtual-box)
  ;;      (finally
  ;;       (.logoff manager virtual-box)))))
  ;; (medium-formats
  ;;  [compute]
  ;;  (let [[manager virtual-box] (connection host port identity credential)]
  ;;    (.. virtual-box getSystemProperties getMediumFormats)))

  pallet.compute/ComputeService
  (nodes
   [compute-service]
   (virtualbox/machines compute-service))

  (ensure-os-family
   [compute-service request]
   request)

  ;; Not implemented
  ;; (build-node-template)


  ;; Run-nodes
  ;; Use the last snapshot of the template machine
  ;; (can not diff an attached medium)
  (run-nodes
   [compute node-type node-count request init-script]
   (session/with-vbox compute [_ virtual-box]
     (try
       (let [os-type-id (find-matching-os
                         node-type (.getGuestOSTypes virtual-box))
             all-machines (.getMachines virtual-box)
             machines (find-matching-machines os-type-id all-machines)
             template-machine (first machines)
             base-folder nil
             id nil
             override false
             tag-name (name (:tag node-type))
             machine-name (->> (range)
                               (map #(machine-name tag-name %))
                               (some (fn [n]
                                       (when (every?
                                              #(not= n (.getName %))
                                              all-machines)
                                         n))))
             ^IMachine machine (.createMachine
                                virtual-box machine-name os-type-id base-folder
                                id override)
             filename-fn (fn [x]
                           (format
                            "/Volumes/My Book/vms/diffdisks/%s.%s.vdi"
                            machine-name x))]
         (.setExtraData machine "/pallet/tag" tag-name)
         (.saveSettings machine)
         (copy-machine
          template-machine machine (.getSystemProperties virtual-box))
         (.saveSettings machine)
         (.registerMachine virtual-box machine)
         (let [machine (model/dry machine compute) ;; (virtualbox/find-machine
                                           ;; virtual-box (.getId machine))
               ]
           (copy-medium-attachments
            virtual-box template-machine machine filename-fn)
           (compute/boot-if-down compute [machine])
           (wait-for-ip machine)
           (pallet.core/lift node-type :phase :bootstrap)
           machine)))))

  (reboot
   [compute nodes]
   (compute/shutdown compute nodes nil)
   (compute/boot-if-down compute nodes))

  (boot-if-down
   [compute nodes]
   (doseq [node nodes]
     (machine/start node)
     ;; (let [^com.sun.xml.ws.commons.virtualbox_3_2.ISession
     ;;       session (#'vmfest.virtualbox.virtualbox/get-session node)
     ;;       virtual-box (#'vmfest.virtualbox.virtualbox/get-vbox node)
     ;;       uuid (:machine-id node)
     ;;       session-type "vrdp"
     ;;       env "DISPLAY:0.0"
     ;;       progress (.openRemoteSession
     ;;                 virtual-box session uuid session-type env)]
     ;;   (with-open [session session]
     ;;     (println "Session for VM" uuid "is opening...")
     ;;     (.waitForCompletion progress 10000)
     ;;     (let [result-code (.getResultCode progress)]
     ;;       (if (zero? result-code)
     ;;         nil
     ;;         true))))
     ))

  (shutdown-node
   [compute node _]
   (logging/info (format "Shutting down %s" (pr-str node)))
   (session/with-no-session node [machine]
     (when (#{org.virtualbox_3_2.MachineState/RUNNING
              org.virtualbox_3_2.MachineState/PAUSED
              org.virtualbox_3_2.MachineState/STUCK} (.getState machine))
       (session/with-remote-session node [session machine]
         (let [console (.getConsole session)
               progress (.powerDown console)]
           (.waitForCompletion progress 15000))))))

  (shutdown
   [compute nodes user]
   (doseq [node nodes]
     (compute/shutdown-node compute node user)))

  (destroy-nodes-with-tag
    [compute tag-name]
    (doseq [machine
            (filter
             #(session/with-no-session % [machine]
                (= tag-name (.getExtraData machine "/pallet/tag")))
             (virtualbox/machines compute))]
      (compute/destroy-node compute machine)))

  (destroy-node
   [compute node]
   {:pre [node]}
   (compute/shutdown-node compute node nil)
   (let [settings-file (session/with-direct-session node [session machine]
                         (remove-media session)
                         (logging/info (format "state %s" (.getState machine)))
                         ;;(.saveSettings machine)
                         (.getSettingsFilePath machine))]
     (session/with-vbox compute [_ virtual-box]
       (logging/info (format "id %s" (.getId (model/soak node virtual-box))))
       (.unregisterMachine virtual-box (.getId (model/soak node virtual-box)))
       (.delete (java.io.File. settings-file)))))
  (close [compute]))

;;;; Compute service
(defmethod implementation/service :virtualbox
  [_ {:keys [url identity credential]
      :or {url "http://localhost:18083/"
           username "test"
           password "test"}
      :as options}]
  (vmfest.virtualbox.model.Server. url identity credential))
