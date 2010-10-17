(ns pallet.compute.vmfest
  "A vmfest provider"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]
   [vmfest.core :as vmfest]))


(defn supported-providers []
  ["virtualbox"])

(deftype VirtualBox [connection ^{:unsynchronized-mutable true} virtual-box]
  pallet.compute.ComputeService
  (nodes [compute-service]
         (vmfest/vm-list virtual-box))
  (ensure-os-family
   [compute-service request]
   request)
  ;; Not implemented
  ;; (build-node-template)
  (run-nodes
   [compute node-type node-count request init-script]
   (dotimes [_ node-count]
     (vmfest/start-vm connection virtual-box "image")))
  ;; (reboot "Reboot the specified nodes")
  ;; (boot-if-down [compute nodes] nil)
  ;; (shutdown-node "Shutdown a node.")
  ;; (shutdown "Shutdown specified nodes")
  )

(defn make-virtualbox [{:keys [host username password]
                        :or {host "localhost"
                             username "test"
                             password "test"}}]
  (let [session-manager (vmfest/create-session-manager host)
        virtual-box (vmfest/create-vbox session-manager username password)]
    (VirtualBox. session-manager virtual-box)))


;;;; Compute service
(defmethod implementation/service :vmfest
  [_ {:keys [host identity credential] :as options}]
  (make-virtualbox options))
