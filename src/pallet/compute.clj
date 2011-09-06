(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [pallet.compute.implementation :as implementation]
   [pallet.utils :as utils]
   [slingshot.core :as slingshot]))


;;; Meta
(defn supported-providers
  "A list of supported provider names.  Each name is suitable to be passed
   to compute-service."
  []
  (implementation/supported-providers))

;;; Compute Service instantiation
(defn compute-service
  "Instantiate a compute service. The provider name should be a recognised
   jclouds provider, or \"node-list\". The other arguments are keyword value
   pairs.
   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values."
  [provider-name
   & {:keys [identity credential extensions node-list endpoint environment]
      :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

;;; Nodes
(defprotocol Node
  (ssh-port [node] "Extract the port from the node's userMetadata")
  (primary-ip [node] "Returns the first public IP for the node.")
  (private-ip [node] "Returns the first private IP for the node.")
  (is-64bit? [node] "64 Bit OS predicate")
  (group-name [node] "Returns the group name for the node.")
  (hostname [node] "TODO make this work on ec2")
  (os-family [node] "Return a node's os-family, or nil if not available.")
  (os-version [node] "Return a node's os-version, or nil if not available.")
  (running? [node])
  (terminated? [node])
  (id [node]))

(defn tag [node] (group-name node))
(defn node-in-group? [group-name node]
  (= (clojure.core/name group-name) (pallet.compute/group-name node)))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))



;;; Actions
(defprotocol ComputeService
  (nodes [compute] "List nodes")
  (run-nodes [compute group-spec node-count user init-script])
  (reboot [compute nodes] "Reboot the specified nodes")
  (boot-if-down
   [compute nodes]
   "Boot the specified nodes, if they are not running.")
  (shutdown-node [compute node user] "Shutdown a node.")
  (shutdown [compute nodes user] "Shutdown specified nodes")
  (ensure-os-family
   [compute group-spec]
   "Called on startup of a new node to ensure group-spec has an os-family
   attached to it.")
  (destroy-nodes-in-group [compute group-name])
  (destroy-node [compute node])
  (images [compute])
  (close [compute]))


(defn nodes-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

(defn node-counts-by-tag [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (inc (get %1 (keyword (tag %2)) 0))) {} nodes))

(defn node?
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (instance? pallet.compute.Node obj))

;;; target mapping
(defn packager
  "Package manager"
  [target]
  (or
   (:packager target)
   (let [os-family (:os-family target)]
     (cond
      (#{:ubuntu :debian :jeos} os-family) :aptitude
      (#{:centos :rhel :amzn-linux :fedora} os-family) :yum
      (#{:arch} os-family) :pacman
      (#{:suse} os-family) :zypper
      (#{:gentoo} os-family) :portage
      (#{:darwin :os-x} os-family) :brew
      :else (slingshot/throw+
             {:type :unknown-packager
              :message (format
                        "Unknown packager for %s - :image %s"
                        os-family target)})))))

(defn base-distribution
  "Base distribution for the target."
  [target]
  (or
   (:base-distribution target)
   (let [os-family (:os-family target)]
     (cond
      (#{:ubuntu :debian :jeos} os-family) :debian
      (#{:centos :rhel :amzn-linux :fedora} os-family) :rh
      (#{:arch} os-family) :arch
      (#{:suse} os-family) :suse
      (#{:gentoo} os-family) :gentoo
      (#{:darwin :os-x} os-family) :os-x
      :else (slingshot/throw+
             {:type :unknown-packager
              :message (format
                        "Unknown base-distribution for %s - target is %s"
                        os-family target)})))))

(defn admin-group
  "User that remote commands are run under"
  [target]
  (case (-> target :image :os-family)
    :centos "wheel"
    :rhel "wheel"
    "adm"))

;;; forward moved functions
;;;   compute-service-from-map
;;;   compute-service-from-settings
;;;   compute-service-from-config-var
;;;   compute-service-from-property
;;;   compute-service-from-config
;;;   compute-service-from-config-file
;;;   service -> configure/compute-service

(utils/fwd-to-configure compute-service-from-map)
(utils/fwd-to-configure compute-service-from-settings)
(utils/fwd-to-configure compute-service-from-config-var)
(utils/fwd-to-configure compute-service-from-property)
(utils/fwd-to-configure compute-service-from-config)
(utils/fwd-to-configure compute-service-from-config-file)
(utils/fwd-to-configure service compute-service)
