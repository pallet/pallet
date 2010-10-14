(ns pallet.compute.node-list
  "A simple node list provider"
  (:require
   [pallet.compute :as compute]
   [clojure.contrib.condition :as condition]))

(defrecord Node
    [name tag ip os-family id ssh-port private-ip 64-bit running]
  pallet.compute.Node
  (ssh-port [node] (:ssh-port node))
  (primary-ip [node] (:ip node))
  (private-ip [node] (:private-ip node))
  (is-64bit? [node] (:64-bit node))
  (tag [node] (:tag node))
  (running? [node] (:running node))
  (terminated? [node] (not (compute/running? node)))
  (node-os-family [node] (:os-family node))
  (hostname [node] (:name node)))

;;; Node utilities
(defn make-node [name tag ip os-family
                 & {:keys [id ssh-port private-ip 64-bit running]
                    :or {ssh-port 80 64-bit true running true}
                    :as options}]
  (Node.
   name
   tag
   ip
   os-family
   (or id (str name "-" (string/replace #"\." "-")))
   ssh-port
   private-ip
   64-bit
   running))

(defrecord NodeList [node-list]
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
  ;; (defn shutdown-node "Shutdown a node.")
  ;; (defn shutdown "Shutdown specified nodes")
  )


(defmethod clojure.core/print-method Node
  [^Node node writer]
  (.write
   writer
   (format
    "%14s\t %s %s\n\t\t %s\n\t\t %s\n\t\t public: %s  private: %s"
    (:tag node)
    (:os-family node)
    (:running node)
    (:ip node)
    (:private-ip))))

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
(defn compute-service
  [node-list-data]
  (NodeList. node-list-data))
