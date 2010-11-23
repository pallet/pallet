(ns pallet.compute.node-list
  "A simple node list provider"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]))


(defn supported-providers []
  ["node-list"])

(defrecord Node
    [name tag ip os-family os-version id ssh-port private-ip is-64bit running]
  pallet.compute.Node
  (ssh-port [node] ssh-port)
  (primary-ip [node] ip)
  (private-ip [node] private-ip)
  (is-64bit? [node] (:is-64bit node))
  (tag [node] tag)
  (running? [node] running)
  (terminated? [node] (not running))
  (os-family [node] os-family)
  (os-version [node] os-version)
  (hostname [node] name)
  (id [node] id))

;;; Node utilities
(defn make-node [name tag ip os-family
                 & {:keys [id ssh-port private-ip is-64bit running os-version]
                    :or {ssh-port 22 is-64bit true running true}
                    :as options}]
  (Node.
   name
   tag
   ip
   os-family
   os-version
   (or id (str name "-" (string/replace ip #"\." "-")))
   ssh-port
   private-ip
   is-64bit
   running))

(defrecord NodeList [node-list]
  pallet.compute.ComputeService
  (nodes [compute-service] node-list)
  (ensure-os-family
   [compute-service request]
   (when (not (-> request :node-type :image :os-family))
     (condition/raise
      :type :no-os-family-specified
       :message "Node list contains a node without os-family")))
  ;; Not implemented
  ;; (build-node-template)
  ;; (run-nodes [node-type node-count request init-script])
  ;; (reboot "Reboot the specified nodes")
  (boot-if-down [compute nodes] nil)
  ;; (shutdown-node "Shutdown a node.")
  ;; (shutdown "Shutdown specified nodes")
  (close [compute]))



(defmethod clojure.core/print-method Node
  [^Node node writer]
  (.write
   writer
   (format
    "%14s\t %s %s public: %s  private: %s  %s"
    (:tag node)
    (:os-family node)
    (:running node)
    (:ip node)
    (:private-ip node)
    (:id node))))

(defn make-localhost-node
  "Make a node representing the local host"
  [& {:keys [name tag ip os-family id]
      :or {name "localhost"
           tag "local"
           ip "127.0.0.1"
           os-family (jvm/os-family)}
      :as options}]
  (apply
   make-node name tag ip os-family
   (apply concat (merge {:id "localhost"} options))))


;;;; Compute service
(defmethod implementation/service :node-list
  [_ {:keys [node-list]}]
  (NodeList. node-list))
