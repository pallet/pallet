(ns pallet.compute.vmfest
  "A vmfest provider"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.core :as core]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]
   [vmfest.virtualbox.vbox :as vbox]
   [vmfest.machine :as machine])
  (:import
   com.sun.xml.ws.commons.virtualbox_3_2.IMedium
   com.sun.xml.ws.commons.virtualbox_3_2.IMachine
   com.sun.xml.ws.commons.virtualbox_3_2.INetworkAdapter))

(defn supported-providers []
  ["virtualbox"])

(defn machine-task [f]
  "Create a task that will be invoked with a machine"
  (fn [session]
    (let [mutable-machine (.getMachine session)]
      (f mutable-machine))))

(defn execute-task-with-return-value
  "Execute a task, capturing the return value."
  [machine task]
  (let [return-value (atom nil)
        latch (java.util.concurrent.CountDownLatch. 1)]
    (machine/execute-task
     machine
     #(do
        (try
          (reset! return-value (task %))
          (finally
           (.countDown latch)))))
     (.await latch)
     @return-value))

(def os-family-name
  {:ubuntu "Ubuntu"
   ;:rhel "RedHat"
   :rhel "RedHat_64"})

(def os-family-from-name
  (zipmap (vals os-family-name) (keys os-family-name)))

(extend-type vmfest.virtualbox.vbox.vbox-machine
  pallet.compute/Node
  (ssh-port [node] 22)
  (primary-ip
   [node]
   (vbox/with-local-or-remote-session node
     (machine/execute-task
      node
      (machine-task
       #(.getGuestPropertyValue
         %
         "/VirtualBox/GuestInfo/Net/0/V4/IP")))))
  (private-ip [node] "")
  (is-64bit?
   [node]
   (vbox/with-local-or-remote-session node
     (re-find
      #"64 bit"
      (machine/execute-task
       node (machine-task #(.getOSTypeId %))))))
  (tag
   [node]
   (vbox/with-local-or-remote-session node
     (machine/execute-task
      node (machine-task #(.getExtraData % "pallet/tag")))))
  (hostname
   [node]
   (vbox/with-local-or-remote-session node
     (machine/execute-task
      node (machine-task #(.getName %)))))
  (os-family
   [node]
   (vbox/with-local-or-remote-session node
     (let [os-name (machine/execute-task
                    node (machine-task #(.getOSTypeId %)))]
       (os-family-from-name os-name os-name))))
  (running? [node] true)
  (terminated? [node] false)
  (id [node] ""))


(defn connection
  [host port identity credential]
  (let [manager (#'vmfest.virtualbox.vbox/create-session-manager host port)
        virtual-box (#'vmfest.virtualbox.vbox/create-vbox
                     manager identity credential)]
    [manager virtual-box]))

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
        (vbox/with-local-or-remote-session vmfest-machine
          (machine/execute-task
           vmfest-machine
           (machine-task
            #(do
               (.attachDevice
                %
                (.getController attachment)
                (.getPort attachment)
                (.getDevice attachment)
                (.getType attachment)
                (.getId medium))
               (.saveSettings %)))))))))

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
  (vbox/with-local-or-remote-session machine
    (loop []
      (let [ip (machine/execute-task
                machine
                (machine-task
                 #(.getGuestPropertyValue
                   % "/VirtualBox/GuestInfo/Net/0/V4/IP")))]
        (when (string/blank? ip)
          (Thread/sleep 500)
          (recur))))))

(defn remove-media [session virtual-box]
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

(deftype VirtualBox [host port identity credential]
  VirtualBoxService
  (os-families
   [compute]
   (let [[manager virtual-box] (connection host port identity credential)]
     (try
       (.getGuestOSTypes virtual-box)
       (finally
        (.logoff manager virtual-box)))))
  (medium-formats
   [compute]
   (let [[manager virtual-box] (connection host port identity credential)]
     (.. virtual-box getSystemProperties getMediumFormats)))

  pallet.compute.ComputeService
  (nodes
   [compute-service]
   (let [[manager virtual-box] (connection host port identity credential)]
     (try
       (doall
        (map
         #(vbox/build-vbox-machine host port identity credential (.getId %))
         (.getMachines virtual-box)))
       (finally
        (.logoff manager virtual-box)))))

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
   (let [[manager virtual-box] (connection host port identity credential)]
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
         (vbox/set-attributes {} machine)
         (.saveSettings machine)
         (copy-machine
          template-machine machine (.getSystemProperties virtual-box))
         (.saveSettings machine)
         (.registerMachine virtual-box machine)
         (try
           (let [machine (vbox/build-vbox-machine
                          host port identity credential
                          (.getId machine))]
             (copy-medium-attachments
              virtual-box template-machine machine filename-fn)
             (compute/boot-if-down compute [machine])
             (wait-for-ip machine)
             (pallet.core/lift node-type :phase :bootstrap)
             machine)
           (finally
            (.logoff manager virtual-box)))))))

  (reboot
   [compute nodes]
   (compute/shutdown compute nodes nil)
   (compute/boot-if-down compute nodes))

  (boot-if-down
   [compute nodes]
   (doseq [node nodes]
     (let [^com.sun.xml.ws.commons.virtualbox_3_2.ISession
           session (#'vmfest.virtualbox.vbox/get-session node)
           virtual-box (#'vmfest.virtualbox.vbox/get-vbox node)
           uuid (:machine-id node)
           session-type "vrdp"
           env "DISPLAY:0.0"
           progress (.openRemoteSession
                     virtual-box session uuid session-type env)]
                                        ;(with-open [session session])
       (println "Session for VM" uuid "is opening...")
       (.waitForCompletion progress 10000)
       (let [result-code (.getResultCode progress)]
         (if (zero? result-code)
           nil
           true)))))

  (shutdown-node
   [compute node _]
   (vbox/with-local-or-remote-session node
     (machine/execute-task
      node
      (fn [session]
        (let [machine (.getMachine session)
              console (.getConsole session)]
          (when (#{org.virtualbox_3_2.MachineState/RUNNING
                   org.virtualbox_3_2.MachineState/PAUSED
                   org.virtualbox_3_2.MachineState/STUCK} (.getState machine))
            (let [ progress (.powerDown console)]
              (.waitForCompletion progress 10000))))))))

  (shutdown
   [compute nodes user]
   (doseq [node nodes]
     (compute/shutdown-node compute node user)))

  (destroy-nodes-with-tag
    [compute tag-name]
    (let [[manager virtual-box] (connection host port identity credential)]
      (try
        (doseq [machine (filter
                        #(= tag-name (.getExtraData % "/pallet/tag"))
                        (.getMachines virtual-box))]
          (compute/destroy-node
           compute
           (vbox/build-vbox-machine
            host port identity credential (.getId machine))))
        (finally
         (.logoff manager virtual-box)))))

  (destroy-node
   [compute node]
   (compute/shutdown-node compute node nil)
   (vbox/with-local-or-remote-session node
     (let [session (#'vmfest.virtualbox.vbox/get-session node)
           virtual-box (#'vmfest.virtualbox.vbox/get-vbox node)
           uuid (:machine-id node)
           machine (.getMachine session)
           settings-file (.getSettingsFilePath machine)]
       (remove-media session virtual-box)
       (.saveSettings machine)
       (.close session)
       (.unregisterMachine virtual-box uuid)
       (.delete (java.io.File. settings-file)))))
  (close [compute]))

;;;; Compute service
(defmethod implementation/service :virtualbox
  [_ {:keys [host port identity credential]
      :or {host "localhost"
           port "18083"
           username "test"
           password "test"}
      :as options}]
  (VirtualBox. host port identity credential))
