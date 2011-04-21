(ns pallet.compute.node-list
  "A simple node list provider"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.environment :as environment]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]))


(defn supported-providers []
  ["node-list"])

(defrecord Node
    [name group-name ip os-family os-version id ssh-port private-ip is-64bit
     running]
  pallet.compute.Node
  (ssh-port [node] ssh-port)
  (primary-ip [node] ip)
  (private-ip [node] private-ip)
  (is-64bit? [node] (:is-64bit node))
  (group-name [node] group-name)
  (running? [node] running)
  (terminated? [node] (not running))
  (os-family [node] os-family)
  (os-version [node] os-version)
  (hostname [node] name)
  (id [node] id))

;;; Node utilities
(defn make-node [name group-name ip os-family
                 & {:keys [id ssh-port private-ip is-64bit running os-version]
                    :or {ssh-port 22 is-64bit true running true}
                    :as options}]
  (Node.
   name
   group-name
   ip
   os-family
   os-version
   (or id (str name "-" (string/replace ip #"\." "-")))
   ssh-port
   private-ip
   is-64bit
   running))

(defrecord NodeList
    [node-list environment]
  pallet.compute.ComputeService
  (nodes [compute-service] node-list)
  (ensure-os-family
   [compute-service group-spec]
   (when (not (-> group-spec :image :os-family))
     (condition/raise
      :type :no-os-family-specified
       :message "Node list contains a node without os-family")))
  ;; Not implemented
  ;; (run-nodes [node-type node-count request init-script])
  ;; (reboot "Reboot the specified nodes")
  (boot-if-down [compute nodes] nil)
  ;; (shutdown-node "Shutdown a node.")
  ;; (shutdown "Shutdown specified nodes")
  (close [compute])
  pallet.environment.Environment
  (environment [_] environment))



(defmethod clojure.core/print-method Node
  [^Node node writer]
  (.write
   writer
   (format
    "%14s\t %s %s public: %s  private: %s  %s"
    (:group-name node)
    (:os-family node)
    (:running node)
    (:ip node)
    (:private-ip node)
    (:id node))))

(defn make-localhost-node
  "Make a node representing the local host"
  [& {:keys [name group-name ip os-family id]
      :or {name "localhost"
           group-name "local"
           ip "127.0.0.1"
           os-family (jvm/os-family)}
      :as options}]
  (apply
   make-node name group-name ip os-family
   (apply concat (merge {:id "localhost"} options))))


;;;; Compute service
(defmethod implementation/service :node-list
  [_ {:keys [node-list environment]}]
  (NodeList.
   (vec
    (map
     #(if (vector? %)
        (apply make-node %)
        %)
     node-list))
   environment))
