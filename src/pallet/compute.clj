(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [pallet.compute.implementation :as implementation]
   [pallet.configure :as configure]
   [pallet.environment :as environment]
   [pallet.utils :as utils]
   [clojure.contrib.condition :as condition]
   [clojure.string :as string])
  (:use
   [clojure.contrib.core :only [-?>]]))


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

(defn compute-service-from-map
  "Create a compute service from a credentials map.
   Uses the :provider, :identity, :credential, :extensions and :node-list keys.
   The :extensions and :node-list keys will be read with read-string if they
   are strings."
  [credentials]
  (let [options {:identity (:identity credentials)
                 :credential (:credential credentials)
                 :extensions (when-let [extensions (:extensions credentials)]
                               (if (string? extensions)
                                 (map
                                  read-string
                                  (string/split extensions #" "))
                                 extensions))
                 :node-list (when-let [node-list (:node-list credentials)]
                              (if (string? node-list)
                                (read-string node-list)
                                node-list))
                 :endpoint (:endpoint credentials)
                 :environment
                 (environment/merge-environments
                  (environment/eval-environment
                   (:environment credentials))
                  (-?> 'cake/*project* resolve var-get :environment))}]
    (when-let [provider (:provider credentials)]
      (apply
       compute-service
       provider
       (apply concat (filter second options))))))

(defn compute-service-from-settings
  "Create a compute service from maven property settings.
   In Maven's settings.xml you can define a profile, that contains
   pallet.compute.provider, pallet.compute.identity and
   pallet.compute.credential values."
  [& profiles]
  (try
    (require 'pallet.maven) ; allow running without maven jars
    (when-let [f (ns-resolve 'pallet.maven 'credentials)]
      (compute-service-from-map (f profiles)))
    (catch ClassNotFoundException _)
    (catch clojure.lang.Compiler$CompilerException _)))

(defn- compute-service-from-var
  [ns sym]
  (utils/find-var-with-require ns sym))

(defn compute-service-from-config-var
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

(defn compute-service-from-config
  "Compute service from a configuration map and a list of active profiles
   (provider keys)."
  [config profiles]
  (let [{:keys [provider identity credential]
         :as options}
        (configure/compute-service-properties config profiles)]
    (when provider
      (apply compute-service
       provider :identity identity :credential credential
       (apply
        concat
        (->
          options
          (dissoc :provider :identity :credential)
          (update-in
           [:environment]
           environment/merge-environments
           (-?> 'cake/*project* resolve var-get :environment))))))))

(defn compute-service-from-config-file
  "Compute service from ~/.pallet/config.clj. Profiles is a sequence of service
   keys to use from the :services map."
  [& profiles]
  (compute-service-from-config
   (configure/pallet-config)
   profiles))

(defn service
  "Instantiate a compute service.

   If passed no arguments, then the compute service is looked up in the
   following order:
   - from a var referenced by the pallet.config.service system property
   - from pallet.config/service if defined
   - the first service in config.xlj
   - the service from the first active profile in settings.xml

   If passed a service name, it is looked up in external
   configuration (~/.pallet/config.clj or ~/.m2/settings.xml). A service name is
   one of the keys in the :services map in config.clj, or a profile id in
   settings.xml.

   When passed a provider name and credentials, the service is instantiated
   based on the credentials.  The provider name should be a recognised provider
   name (see `pallet.compute/supported-providers` to obtain a list of these).

   The other arguments are keyword value pairs.
   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values."
  ([]
     (or
      (compute-service-from-property)
      (compute-service-from-config-var)
      (compute-service-from-config-file)
      (compute-service-from-settings)))
  ([service-name]
     (or
      (compute-service-from-config-file service-name)
      (compute-service-from-settings service-name)))
  ([provider-name
    & {:keys [identity credential extensions node-list endpoint environment]
       :as options}]
     (apply compute-service provider-name (apply concat options))))

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
      (#{:ubuntu :debian :jeos} os-family) :aptitude
      (#{:centos :rhel :amzn-linux :fedora} os-family) :yum
      (#{:arch} os-family) :pacman
      (#{:suse} os-family) :zypper
      (#{:gentoo} os-family) :portage
      (#{:darwin :os-x} os-family) :brew
      :else (condition/raise
             :type :unknown-packager
             :message (format
                       "Unknown packager for %s - :image %s"
                       os-family target))))))

(defn admin-group
  "User that remote commands are run under"
  [target]
  (case (-> target :image :os-family)
    :centos "wheel"
    :rhel "wheel"
    "adm"))
