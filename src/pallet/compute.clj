(ns pallet.compute
  "Additions to the jclouds compute interface"
  (:require
   [pallet.compute.jclouds :as jclouds] ; remove when we have protocols
   [pallet.compute.jvm :as jvm]
   [pallet.utils :as utils]
   [pallet.maven :as maven]
   [pallet.execute :as execute]
   [clojure.contrib.condition :as condition]))


;;; Meta
(defn supported-clouds []
  (jclouds/supported-clouds))

;;;; Compute service
(defn compute-service-from-settings
  "Create a jclouds compute service from maven ~/.m2/settings.xml.  If
   extensions are listed they are used, otherwise :log4j and
   :ssh are automatically added."
  [& extensions]
  (apply jclouds/compute-service-from-settings (maven/credentials) extensions))

(defn compute-from-options
  [current-value {:keys [compute compute-service] :as options}]
  (jclouds/compute-from-options current-value options))

;;; Predicates
(defn running? [node]
  (jclouds/running? node))

;;; Node utilities



;;; Node properties
(defn ssh-port
  "Extract the port from the node's userMetadata"
  [node]
  (jclouds/ssh-port node))

(defn primary-ip
  "Returns the first public IP for the node."
  [node]
  (jclouds/primary-ip node))

(defn private-ip
  "Returns the first private IP for the node."
  [node]
  (jclouds/private-ip node))

(defn is-64bit?
  [node]
  (jclouds/is-64bit? node))

(defn node-has-tag? [tag node]
  (= (name tag) (jclouds/tag node)))

(defn tag
  "Returns the tag for the node."
  [node]
  (jclouds/tag node))


;;; Nodes
(defn nodes-with-details
  [compute-service]
  (jclouds/nodes-with-details compute-service))

(defn nodes-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

(defn node-counts-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (inc (get %1 (keyword (tag %2)) 0))) {} nodes))



;;; Actions
(defn run-nodes
  [node-type node-count request init-script]
  (jclouds/run-nodes
   node-type node-count request init-script))

(defn reboot
  "Reboot the specified nodes"
  [nodes compute]
  (jclouds/reboot nodes compute))

(defn boot-if-down
  "Boot the specified nodes, if they are not running."
  [nodes compute]
  (map #(reboot % compute)
       (filter jclouds/terminated? nodes)))

(defn shutdown-node
  "Shutdown a node."
  [node user compute]
  (let [ip (primary-ip node)]
    (if ip
      (execute/remote-sudo ip "shutdown -h 0" user))))

(defn shutdown
  "Shutdown specified nodes"
  [nodes user compute]
  (dorun (map #(shutdown-node % compute) nodes)))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))

(defn node-os-family
  "Return a nodes os-family, or nil if not available."
  [node]
  (jclouds/node-os-family node))

(defn node-locations
  "Return locations of a node as a seq."
  [node]
  (jclouds/node-locations node))

(defn image-string
  [image]
  (jclouds/image-string image))

(defn os-string
  [os]
  (jclouds/os-string os))

(defn location-string
  [location]
  (jclouds/location-string location))


(defn make-localhost-node
  "Make a node representing the local host"
  []
  (jclouds/make-localhost-node))

(defn local-request
  "Create a request map for localhost"
  []
  (let [node (jclouds/make-localhost-node)]
    {:target-node node
     :all-nodes [node]
     :target-nodes [node]
     :node-type {:image {:os-family (jvm/os-family)}}}))

(defn hostname
  "TODO make this work on ec2"
  [node]
  (jclouds/hostname node))


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

(defn ensure-os-family [request]
  (jclouds/ensure-os-family request))
