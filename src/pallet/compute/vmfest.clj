(ns pallet.compute.vmfest
  "A vmfest provider"
  (:require
   [vmfest.virtualbox.virtualbox :as virtualbox]
   [vmfest.virtualbox.machine :as machine]
   [vmfest.virtualbox.model :as model]
   [vmfest.manager :as manager]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.core :as core]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging]))

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
   (manager/get-ip node))
  (private-ip [node] "")
  (is-64bit?
   [node]
   (let [os-type-id (:os-type-id (manager/as-map node))]
     (re-find #"64 bit" os-type-id)))
  (tag
   [node]
   (manager/get-extra-data node "/pallet/tag"))
  (hostname
   [node]
   (:name (manager/as-map node)))
  (os-family
   [node]
   (let [os-name (:os-type-id (manager/as-map node))]
     (os-family-from-name os-name os-name)
     :centos  ;; hack!
     ))
  (os-version
   [node]
   "5.5")
  (running? [node] true)
  (terminated? [node] false)
  (id [node] ""))

(defn nil-if-blank [x]
  (if (string/blank? x) nil x))

(defn wait-for-ip
  "Wait for the machines IP to become available."
  [machine]
  (loop []
    (try
      (let [ip (try (manager/get-ip machine)
                    (catch org.virtualbox_3_2.RuntimeFaultMsg e
                      (logging/warn
                       (format "wait-for-ip: Machine %s not started yet..." machine)))
                    (catch clojure.contrib.condition.Condition e
                      (logging/warn
                       (format "wait-for-ip: Machine %s is not accessible yet..." machine))))]
        (when (string/blank? ip)
          (Thread/sleep 500)
          (recur))))))


(defn machine-name
  "Generate a machine name"
  [tag n]
  (format "%s-%s" tag n))

(defprotocol VirtualBoxService
  (os-families [compute] "Return supported os-families")
  (medium-formats [compute] "Return supported medium-formats"))

(defn node-data [m]
  (let [node-map (manager/as-map m)
        attributes (select-keys node-map [:name :description :session-state])
        open? (= :open (:session-state node-map))
        ip (when open? (manager/get-ip m))
        tag (when open? (manager/get-extra-data m "/pallet/tag"))]
    (into attributes {:ip ip :tag tag}) ))

(defn node-infos [compute-service]
  (let [nodes (manager/machines compute-service)]
    (map node-data nodes)))

(defn create-node [compute node-type machine-name image-id tag-name]
  (let [machine (manager/instance compute machine-name image-id :micro)]
    (manager/set-extra-data machine "/pallet/tag" tag-name)
       (manager/start machine :session-type "vrdp")
       (wait-for-ip machine)
       (pallet.core/lift node-type :phase :bootstrap)))

(extend-type vmfest.virtualbox.model.Server
  pallet.compute/ComputeService
  (nodes
   [compute-service]
   (manager/machines compute-service)
   #_(node-infos compute-service))

  (ensure-os-family
   [compute-service request]
   request)

  ;; Not implemented
  ;; (build-node-template)

  (run-nodes
   [compute node-type node-count request init-script]
   (try
     (let [image-id (-> node-type :image :image-id)
           tag-name (name (:tag node-type))
           current-machines-in-tag (filter
                                    #(= tag-name (manager/get-extra-data % "/pallet/tag")) 
                                    (manager/machines compute))
           current-machine-names (into #{} (map #(:name (manager/as-map %)) current-machines-in-tag))
           target-indices (range (+ node-count (count current-machines-in-tag)))
           target-machine-names (into #{} (map #(machine-name tag-name %) target-indices))
           target-machines-already-existing (clojure.set/intersection
                                             current-machine-names
                                             target-machine-names)
           target-machines-to-create (clojure.set/difference
                                      target-machine-names
                                      target-machines-already-existing)]
       (logging/debug (str "current-machine-names " current-machine-names))
       (logging/debug (str "target-machine-names " target-machine-names))
       (logging/debug (str "target-machines-already-existing " target-machines-already-existing))
       (logging/debug (str "target-machines-to-create" target-machines-to-create))
       (doall
        (map #(create-node compute node-type % image-id tag-name) target-machines-to-create)))))

  (reboot
   [compute nodes]
   (compute/shutdown compute nodes nil)
   (compute/boot-if-down compute nodes))

  (boot-if-down
   [compute nodes]
   (doseq [node nodes]
     (manager/start node)))

  (shutdown-node
   [compute node _]
   ;; todo: wait for completion
   (logging/info (format "Shutting down %s" (pr-str node)))
   (manager/power-down node)
   (manager/wait-for-machine-state node [:powered-off] 10000))

  (shutdown
   [compute nodes user]
   (doseq [node nodes]
     (compute/shutdown-node compute node user)))

  (destroy-nodes-with-tag
    [compute tag-name]
    (doseq [machine
            (filter
             #(= tag-name (manager/get-extra-data % "/pallet/tag")) 
             (manager/machines compute))]
      (compute/destroy-node compute machine)))

  (destroy-node
   [compute node]
   {:pre [node]}
   (compute/shutdown-node compute node nil)
   (manager/destroy node))
  (close [compute]))

;;;; Compute service
(defmethod implementation/service :virtualbox
  [_ {:keys [url identity credential]
      :or {url "http://localhost:18083/"
           identity "test"
           credential "test"}
      :as options}]
  (vmfest.virtualbox.model.Server. url identity credential))
