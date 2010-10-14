(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [pallet.compute.jvm :as jvm]
   [pallet.utils :as utils]
   [pallet.maven :as maven]
   [pallet.execute :as execute]
   [clojure.contrib.condition :as condition]))


;;; Compute Service instantiation
(defmulti service
  "Instantiate a compute service based on the given arguments"
  (fn [first & _] (keyword first)))

(defn compute-from-options
  [current-value {:keys [compute compute-service]}]
  (or current-value
      compute
      (and compute-service
           (service
            (:provider compute-service)
            :identity (:identity compute-service)
            :credential (:credential compute-service)
            :extensions (or (:extensions compute-service))))))


;;; Nodes
(defprotocol Node
  (ssh-port [node] "Extract the port from the node's userMetadata")
  (primary-ip [node] "Returns the first public IP for the node.")
  (private-ip [node] "Returns the first private IP for the node.")
  (is-64bit? [node] "64 Bit OS predicate")
  (tag [node] "Returns the tag for the node.")
  (hostname [node] "TODO make this work on ec2")
  (node-os-family [node] "Return a nodes os-family, or nil if not available.")
  (running? [node])
  (terminated? [node]))

(defn node-has-tag? [tag node]
  (= (name tag) (tag node)))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))



;;; Actions
(defprotocol ComputeService
  (nodes-with-details [compute] "List nodes")
  (run-nodes [compute node-type node-count request init-script])
  (reboot [compute nodes] "Reboot the specified nodes")
  (boot-if-down
   [compute nodes]
   "Boot the specified nodes, if they are not running.")
  (shutdown-node [compute node user] "Shutdown a node.")
  (shutdown [compute nodes user] "Shutdown specified nodes")
  (ensure-os-family
   [compute request]
   "Called on startup of a new node to ensure request has an os-family attached
   to it."))


(defn nodes-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

(defn node-counts-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (inc (get %1 (keyword (tag %2)) 0))) {} nodes))

;;; target mapping
(defn packager
  "Package manager"
  [target]
  (or
   (:packager target)
   (let [os-family (:os-family target)]
     (cond
      (#{:ubuntu :debian :jeos :fedora} os-family) :aptitude
      (#{:centos :rhel :amzn-linux} os-family) :yum
      (#{:arch} os-family) :pacman
      (#{:suse} os-family) :zypper
      (#{:gentoo} os-family) :portage
      (#{:darwin} os-family) :brew
      :else (condition/raise
             :type :unknown-packager
             :message (format
                       "Unknown packager for %s : :image %s"
                       os-family target))))))
