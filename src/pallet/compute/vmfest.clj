(ns pallet.compute.vmfest
  "A vmfest provider.

   An example service configuration in ~/.pallet/config.clj

       :vb {:provider \"virtualbox\"
            :images {:centos-5-3 {:description \"CentOS 5.3 32bit\"
                                  :uuid \"4697bdf7-7acf-4a20-8c28-e20b6bb58e25\"
                                  :os-family :centos
                                  :os-version \"5.3\"
                                  :os-type-id \"RedHat\"}
                     :ubuntu-10-04 {:description \"Ubuntu 10.04 32bit\"
                                    :uuid \"8a31e3aa-0d46-41a5-936d-25130dcb16b7\"
                                    :os-family :ubuntu
                                    :os-version \"10.04\"
                                    :os-type-id \"Ubuntu\"
                                    :username
                                    :password}}
            :model-path \"/Volumes/My Book/vms/disks\"
            :node-path \"/Volumes/My Book/vms/nodes\"}

   The uuid's can be found using vboxmanage
       vboxmanage list hdds

   The images are disks that are immutable.  The virtualbox extensions need
   to be installed on the image."
  (:require
   [clojure.contrib.condition :as condition]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action-plan :as action-plan]
   [pallet.blobstore :as blobstore]
   [pallet.common.filesystem :as filesystem]
   [pallet.compute :as compute]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.futures :as futures]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [vmfest.manager :as manager]
   [vmfest.virtualbox.enums :as enums]
   [vmfest.virtualbox.image :as image]
   [vmfest.virtualbox.machine :as machine]
   [vmfest.virtualbox.model :as model]
   [vmfest.virtualbox.session :as session]
   [vmfest.virtualbox.virtualbox :as virtualbox]))

(defn supported-providers []
  ["virtualbox"])

(def os-family-name
  {:ubuntu "Ubuntu"
   :centos "RedHat"
   ;:rhel "RedHat"
   :rhel "RedHat_64"})

(def ip-tag "/pallet/ip")
(def group-name-tag "/pallet/group-name")
(def os-family-tag "/pallet/os-family")
(def os-version-tag "/pallet/os-version")

(def *vm-session-type* "headless") ; gui, headless or sdl

(def os-family-from-name
  (zipmap (vals os-family-name) (keys os-family-name)))

(extend-type vmfest.virtualbox.model.Machine
  pallet.node/Node
  (ssh-port [node] 22)
  (primary-ip
   [node]
   (condition/handler-case
    :type
    (manager/get-ip node)
    (handle :vbox-runtime
      (manager/get-extra-data node ip-tag))))
  (private-ip [node] nil)
  (is-64bit?
   [node]
   (let [os-type-id (session/with-no-session node [m] (.getOSTypeId m))]
     (boolean (re-find #"_64" os-type-id))))
  (group-name
   [node]
   (let [group-name (manager/get-extra-data node group-name-tag)]
     (if (string/blank? group-name)
       (manager/get-extra-data node group-name-tag)
       group-name)))
  (hostname
   [node]
   (session/with-no-session node [m]
     (.getName m)))
  (os-family
   [node]
   (let [os-name (session/with-no-session node [m] (.getOSTypeId m))]
     (or
      (when-let [os-family (manager/get-extra-data node os-family-tag)]
        (when-not (string/blank? os-family)
          (keyword os-family)))
      (os-family-from-name os-name os-name)
      :centos) ;; hack!
     ))
  (os-version
   [node]
   (or
    (manager/get-extra-data node os-version-tag)
    "5.3"))
  (running?
   [node]
   (and
    (session/with-no-session node [vb-m] (.getAccessible vb-m))
    (= :running (manager/state node))))
  (terminated? [node] false)
  (id [node] (:id node)))

(defn nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn- current-time-millis []
  (System/currentTimeMillis))

(defn wait-for-ip
  "Wait for the machines IP to become available."
  ([machine] (wait-for-ip machine 300000))
  ([machine timeout]
     (let [timeout (+ (current-time-millis) timeout)]
       (loop []
         (try
           (let [ip (try (manager/get-ip machine)
                         (catch org.virtualbox_4_0.VBoxException e
                           (logging/warnf
                            "wait-for-ip: Machine %s not started yet..."
                            machine))
                         (catch clojure.contrib.condition.Condition e
                           (logging/warnf
                            "wait-for-ip: Machine %s is not accessible yet..."
                            machine)))]
             (if (and (string/blank? ip) (< (current-time-millis) timeout))
               (do
                 (Thread/sleep 2000)
                 (recur))
               ip)))))))


(defn machine-name
  "Generate a machine name"
  [group-name n]
  (format "%s-%s" group-name n))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))

(defn node-data [m]
  (let [attributes (session/with-no-session m [im]
                     [(.getName im)
                      (.getDescription im)
                      (.getSessionState im)])
        open? (= :open
                 (session/with-no-session m [im]
                   (enums/session-state-to-key
                    (.getSessionState im))))
        ip (when open? (manager/get-ip m))
        group-name (when open? (manager/get-extra-data m group-name-tag))]
    (into attributes {:ip ip :group-name group-name}) ))

(defn node-infos [compute-service]
  (let [nodes (manager/machines compute-service)]
    (map node-data nodes)))

(defn add-sata-controller [m]
  {:pre [(model/IMachine? m)]}
  (machine/add-storage-controller m "SATA Controller" :sata))

(defn basic-config [m {:keys [memory-size cpu-count] :as parameters}]
  (let [parameters (merge {:memory-size 512 :cpu-count 1} parameters)]
    (manager/configure-machine m parameters)
    (manager/set-bridged-network m "en1: AirPort")
    (manager/add-ide-controller m)))

(def hardware-parameters
  {:min-ram :memory-size
   :min-cores :cpu-count})

(def hardware-config
  {:bridged-network (fn [m iface] (manager/set-bridged-network m iface))})

(defn machine-model-with-parameters
  "Set a machine basic parameters and default configuration"
  [m image]
  (let [hw-keys (filter hardware-parameters (keys image))]
    (basic-config
     m (zipmap (map hardware-parameters hw-keys) (map image hw-keys)))))

(defn machine-model
  "Construct a machine model function from a node image spec"
  [image]
  (fn [m]
    (logging/debugf "Machine model for %s" image)
    (machine-model-with-parameters m image)
    (doseq [kw (filter hardware-config (keys image))]
      ((hardware-config kw) m (image kw)))))

(defn create-node
  [compute node-path node-spec machine-name images image-id machine-models
   group-name init-script user]
  {:pre [image-id]}
  (logging/tracef "Creating node from image-id: %s" image-id)
  (let [machine (binding [manager/*images* images
                          manager/*machine-models* machine-models]
                  (manager/instance
                   compute machine-name image-id :micro node-path))
        image (image-id images)]
    (manager/set-extra-data machine group-name-tag group-name)
    (manager/set-extra-data machine os-family-tag (name (:os-family image)))
    (manager/set-extra-data machine os-version-tag (:os-version image))
    ;; (manager/add-startup-command machine 1 init-script )
    (manager/start
     machine
     :session-type (or
                    (:session-type node-spec)
                    *vm-session-type*))
    (logging/trace "Wait to allow boot")
    (Thread/sleep 15000)                ; wait minimal time for vm to boot
    (logging/trace "Waiting for ip")
    (when (string/blank? (wait-for-ip machine))
      (condition/raise
       :type :no-ip-available
       :message "Could not determine IP address of new node"))
    (Thread/sleep 4000)
    (logging/tracef "Bootstrapping %s" (manager/get-ip machine))
    (script/with-script-context
      (action-plan/script-template-for-server {:image image})
      (stevedore/with-script-language :pallet.stevedore.bash/bash
        (let [user (if (:username image)
                     (pallet.utils/make-user
                      (:username image)
                      :password (:password image)
                      :no-sudo (:no-sudo image)
                      :sudo-password (:sudo-password image))
                     user)]
          (execute/remote-sudo
           (manager/get-ip machine) init-script user
           {:pty (not (#{:arch :fedora} (:os-family image)))}))))
    machine))

(defn- equality-match
  [image-properties kw arg]
  (= (image-properties kw) arg))

(defn- regexp-match
  [image-properties kw arg]
  (when-let [value (image-properties kw)]
    (re-matches (re-pattern arg) value)))

(def template-matchers
  {:os-version-matches (fn [image-properties kw arg]
                         (regexp-match image-properties :os-version arg))})

(defn image-from-template
  "Use the template to select an image from the image map."
  [images template]
  (let [template-to-match (utils/dissoc-keys
                           template
                           (concat
                            [:image-id :inbound-ports]
                            (keys hardware-config)
                            (keys hardware-parameters)))]
    (logging/debugf "Looking for %s in %s" template-to-match images)
    (if-let [image-id (:image-id template)]
      (image-id images)
      (->
       (filter
        (fn image-matches? [[image-name image-properties]]
          (every?
           #(((first %) template-matchers equality-match)
             image-properties (first %) (second %))
           template-to-match))
        images)
       ffirst))))

(defn serial-create-nodes
  "Create all nodes for a group in parallel."
  [target-machines-to-create server node-path node-spec images image-id
   machine-models group-name init-script user]
  (doall
   (for [name target-machines-to-create]
     (create-node
      server node-path node-spec name images image-id machine-models group-name
      init-script user))))

(defn parallel-create-nodes
  "Create all nodes for a group in parallel."
  [target-machines-to-create server node-path node-spec images image-id
   machine-models group-name init-script user]
  ;; the doseq ensures that all futures are completed before
  ;; returning
  (->>
   (for [name target-machines-to-create]
     (future
       (create-node
        server node-path node-spec name images image-id
        machine-models group-name init-script user)))
   doall ;; doall forces creation of all futures before any deref
   futures/add
   (map #(futures/deref-with-logging % "Start of node"))
   (filter identity)
   doall))

(def
  ^{:dynamic true
    :doc (str "The buffer size (in bytes) for the piped stream used to implement
    the :stream option for :out. If your ssh commands generate a high volume of
    output, then this buffer size can become a bottleneck. You might also
    increase the frequency with which you read the output stream if this is an
    issue.")}
  *piped-stream-buffer-size* (* 1024 1024))

(defn- piped-streams
  []
  (let [os (java.io.PipedOutputStream.)]
    [os (java.io.PipedInputStream. os *piped-stream-buffer-size*)]))

(defn gzip [from to]
  (with-open [input (io/input-stream from)
              output (java.util.zip.GZIPOutputStream. (io/output-stream to))]
    (io/copy input output)))

(defprotocol ImageManager
  (install-image [service url {:as options}]
    "Install the image from the specified `url`")
  (publish-image [service image blobstore container {:keys [path] :as options}]
    "Publish the image to the specified blobstore container")
  (has-image? [service image-key]
    "Predicate to test for the presence of a specific image"))

(deftype VmfestService
    [server images locations environment]
  pallet.compute/ComputeService
  (nodes [compute-service] (manager/machines server))

  (ensure-os-family [compute-service group] group)

  (run-nodes
    [compute-service group-spec node-count user init-script]
    (try
      (let [template (->> [:image :hardware :location :network :qos]
                          (select-keys group-spec)
                          vals
                          (reduce merge))
            _ (logging/infof "Template %s" template)
            image-id (or (image-from-template @images template)
                         (throw (RuntimeException.
                                 (format "No matching image for %s in"
                                         (pr-str (:image group-spec))
                                         (@images)))))
            group-name (name (:group-name group-spec))
            machines (filter
                      #(session/with-no-session % [vb-m] (.getAccessible vb-m))
                      (manager/machines server))
            current-machines-in-group (filter
                                       #(= group-name
                                           (manager/get-extra-data
                                            % group-name-tag))
                                       machines)
            current-machine-names (into #{}
                                        (map
                                         #(session/with-no-session % [m]
                                            (.getName m))
                                         current-machines-in-group))
            target-indices (range (+ node-count
                                     (count current-machines-in-group)))
            target-machine-names (into #{}
                                       (map
                                        #(machine-name group-name %)
                                        target-indices))
            target-machines-already-existing (clojure.set/intersection
                                              current-machine-names
                                              target-machine-names)
            target-machines-to-create (clojure.set/difference
                                       target-machine-names
                                       target-machines-already-existing)]
        (logging/debug (str "current-machine-names " current-machine-names))
        (logging/debug (str "target-machine-names " target-machine-names))
        (logging/debug (str "target-machines-already-existing "
                            target-machines-already-existing))
        (logging/debug (str "target-machines-to-create"
                            target-machines-to-create))

        ((get-in environment [:algorithms :vmfest :create-nodes-fn]
                 parallel-create-nodes)
         target-machines-to-create server (:node-path locations)
         group-spec @images image-id
         {:micro (machine-model template)}
         group-name init-script user))))

  (reboot
    [compute nodes]
    (compute/shutdown server nodes nil)
    (compute/boot-if-down server nodes))

  (boot-if-down
    [compute nodes]
    (doseq [node nodes]
      (manager/start node)))

  (shutdown-node
    [compute node _]
    ;; todo: wait for completion
    (logging/infof "Shutting down %s" (pr-str node))
    (manager/power-down node)
    (if-let [state (manager/wait-for-machine-state node [:powered-off] 300000)]
      (logging/infof "Machine state is %s" state)
      (logging/warn "Failed to wait for power down completion"))
    (manager/wait-for-lockable-session-state node 2000))

  (shutdown
    [compute nodes user]
    (doseq [node nodes]
      (compute/shutdown-node server node user)))

  (destroy-nodes-in-group
    [compute group-name]
    (let [nodes (locking compute ;; avoid disappearing machines
                  (filter
                   #(and
                     (compute/running? %)
                     (= group-name (manager/get-extra-data % group-name-tag)))
                   (manager/machines server)))]
      (doseq [machine nodes]
        (compute/destroy-node compute machine))))

  (destroy-node
    [compute node]
    {:pre [node]}
    (compute/shutdown-node compute node nil)
    (manager/destroy node))

  (images [compute]
    @images)

  (close [compute])
  pallet.environment.Environment
  (environment [_] environment)
  ImageManager
  (install-image
    [compute url {:as options}]
    (logging/infof "installing image to %s" (:model-path locations))
    (when-let [job (image/setup-model
                    url server :models-dir (:model-path locations))]
      (swap! images merge (:meta job))))
  (publish-image [service image-kw blobstore container {:keys [path]}]
    (if-let [image (image-kw @images)]
      (session/with-vbox server [_ vbox]
        (let [medium (virtualbox/find-medium vbox (:uuid image))
              file (java.io.File. (.getLocation medium))]
          (filesystem/with-temp-file [gzip-file]
            (logging/infof "gzip to %s" (.getPath gzip-file))
            (gzip file gzip-file)
            (logging/infof "put gz %s" (.getPath gzip-file))
            (try
              (blobstore/put
               blobstore container (or path (str (.getName file) ".gz"))
               gzip-file)
              (catch Exception e
                (logging/error e "Upload failed"))))
          (logging/info "put meta")
          (blobstore/put
           blobstore container
           (string/replace (or path (.getName file)) #"\.vdi.*" ".meta")
           (pr-str {image-kw (dissoc image :uuid)}))))
      (let [msg (format
                 "Could not find image %s. Known images are %s."
                 image-kw (keys @images))]
        (logging/error msg)
        (condition/raise
         {:type :pallet/unkown-image
          :image image-kw
          :known-images (keys @images)
          :message msg}))))
  (has-image? [_ image-kw]
    ((or @images {}) image-kw)))

(defn add-image [compute url & {:as options}]
  (install-image compute url options))

;;;; Compute service
(defmethod implementation/service :virtualbox
  [_ {:keys [url identity credential images node-path model-path locations
             environment]
      :or {url "http://localhost:18083/"
           identity "test"
           credential "test"
           model-path (manager/default-model-path)
           node-path (manager/default-node-path)}
      :as options}]
  (let [locations (or locations
                      {:local {:node-path node-path :model-path model-path}})
        images (merge (manager/load-models :model-path model-path) images)]
    (VmfestService.
     (vmfest.virtualbox.model.Server. url identity credential)
     (atom images)
     (val (first locations))
     environment)))

(defmethod clojure.core/print-method vmfest.virtualbox.model.Machine
  [node writer]
  (.write
   writer
   (format
    "%14s\t %14s\t public: %s"
    (try (compute/hostname node) (catch Throwable e "unknown"))
    (try (compute/group-name node) (catch Throwable e "unknown"))
    (try (compute/primary-ip node) (catch Throwable e "unknown")))))
