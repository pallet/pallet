(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [pallet.compute.implementation :as implementation]
   [pallet.utils :as utils]
   [pallet.maven :as maven]
   [pallet.execute :as execute]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string]))


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
     :identity     username or key
     :credential   password or secret
     :extensions   extension modules for jclouds
     :node-list    a list of nodes for the \"node-list\" provider."
  [provider-name
   & {:keys [identity credential extensions node-list] :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

(defn compute-service-from-settings
  "Create a compute service from maven property settings.
   In Maven's settings.xml you can define a profile, that contains
   pallet.compute.provider, pallet.compute.identity and
   provider.compute.credential values."
  [& profiles]
  (let [credentials (pallet.maven/credentials profiles)
        options {:identity (:compute-identity credentials)
                 :credential (:compute-credential credentials)
                 :extensions (when-let [extensions (:compute-extensions
                                                    credentials)]
                               (map
                                read-string
                                (string/split extensions #" ")))
                 :node-list (when-let [node-list (:node-list credentials)]
                              (read-string node-list))}]
    (when-let [provider (:compute-provider credentials)]
      (apply
       compute-service
       provider
       (apply concat (filter second options))))))

(defn- compute-service-from-var
  [ns sym]
  (try
    (when-not (find-ns ns)
      (require ns))
    (when-let [v (ns-resolve ns sym)]
      (var-get v))
    (catch Throwable _)))

(defn compute-service-from-config
  "Checks to see if pallet.config/service is a var, and if so returns its
  value."
  []
  (compute-service-from-var 'pallet.config 'service))

(defn compute-service-from-property
  "If the pallet.config.service property is defined, and refers to a var, then
   return its value."
  []
  (when-let [property (System/getProperty "pallet.config.service")]
    (when-let [sym-names (and (re-find #"/" property)
                              (string/split property #"/"))]
      (compute-service-from-var
       (symbol (first sym-names)) (symbol (second sym-names))))))

;;; Nodes
(defprotocol Node
  (ssh-port [node] "Extract the port from the node's userMetadata")
  (primary-ip [node] "Returns the first public IP for the node.")
  (private-ip [node] "Returns the first private IP for the node.")
  (is-64bit? [node] "64 Bit OS predicate")
  (tag [node] "Returns the tag for the node.")
  (hostname [node] "TODO make this work on ec2")
  (os-family [node] "Return a nodes os-family, or nil if not available.")
  (running? [node])
  (terminated? [node])
  (id [node]))

(defn node-has-tag? [tag-name node]
  (= (clojure.core/name tag-name) (tag node)))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))



;;; Actions
(defprotocol ComputeService
  (nodes [compute] "List nodes")
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
   to it.")
  (destroy-nodes-with-tag [compute tag-name])
  (destroy-node [compute node])
  (close [compute]))


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
      (#{:darwin :os-x} os-family) :brew
      :else (condition/raise
             :type :unknown-packager
             :message (format
                       "Unknown packager for %s - :image %s"
                       os-family target))))))
