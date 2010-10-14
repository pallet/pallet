(ns pallet.compute.node-list
  "A simple node list provider"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]))

(defrecord Node
    [name tag ip os-family id ssh-port private-ip is-64bit running]
  pallet.compute.Node
  (ssh-port [node] (:ssh-port node))
  (primary-ip [node] (:ip node))
  (private-ip [node] (:private-ip node))
  (is-64bit? [node] (:is-64bit node))
  (tag [node] (:tag node))
  (running? [node] (:running node))
  (terminated? [node] (not (:running node)))
  (node-os-family [node] (:os-family node))
  (hostname [node] (:name node))
  (id [node] (:id node)))

;;; Node utilities
(defn make-node [name tag ip os-family
                 & {:keys [id ssh-port private-ip is-64bit running]
                    :or {ssh-port 22 is-64bit true running true}
                    :as options}]
  (Node.
   name
   tag
   ip
   os-family
   (or id (str name "-" (string/replace ip #"\." "-")))
   ssh-port
   private-ip
   is-64bit
   running))

(defrecord NodeList [node-list]
  pallet.compute.ComputeService
  (nodes-with-details [compute-service]
    (:node-list compute-service))
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
  ;; (boot-if-down "Boot the specified nodes, if they are not running.")
  ;; (shutdown-node "Shutdown a node.")
  ;; (shutdown "Shutdown specified nodes")
  )



(defmethod clojure.core/print-method Node
  [^Node node writer]
  (.write
   writer
   (format
    "%14s\t %s %s public: %s  private: %s"
    (:tag node)
    (:os-family node)
    (:running node)
    (:ip node)
    (:private-ip node))))

(defn make-localhost-node
  "Make a node representing the local host"
  [& {:keys [name tag ip os-family]
      :or {name "localhost"
           tag "local"
           ip "127.0.0.1"
           os-family (jvm/os-family)}
      :as options}]
  (apply
   make-node name tag ip os-family
   (apply concat options)))


;;;; Compute service
(defmethod compute/service :node-list
  [_ & {:keys [node-list]}]
  (NodeList. node-list))
