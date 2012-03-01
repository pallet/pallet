(ns pallet.configure
  "Pallet configuration using ~/.pallet/config.clj, the pallet.config namespace
   or from settings.xml.

   config.clj should be in ~/.pallet or a directory specified by the PALLET_HOME
   environment variable.

   service definitions can also be specified as clojure maps in
   ~/.pallet/services/*.clj"
  (:require
   [pallet.blobstore :as blobstore]
   [pallet.common.deprecate :as deprecate]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.utils :as utils]
   [clojure.java.io :as java-io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [clojure.walk :as walk])
  (:use
   [clojure.core.incubator :only [-?>]]))

(def ^{:private true
       :doc "A var to be set by defpallet, so that it may be loaded from any
             namespace"}
  config nil)

(defn- unquote-vals [args]
  (walk/walk
   (fn [item]
     (cond (and (seq? item) (= `unquote (first item))) (second item)
           ;; needed if we want fn literals to be usable by eval-in-project
           (and (seq? item) (= 'fn (first item))) (list 'quote item)
           (symbol? item) (list 'quote item)
           :else (unquote-vals item)))
   identity
   args))

(defmacro defpallet
  "Top level macro for the pallet config.clj file."
  [& {:keys [provider identity credential providers admin-user]
      :as config-options}]
  `(let [m# (zipmap
             ~(cons 'list (keys config-options))
             ~(cons 'list (unquote-vals (vals config-options))))]
    (alter-var-root #'config (fn [_#] m#))))

(defn- read-config
  [file]
  (try
    (use '[pallet.configure :only [defpallet]])
    (load-file file)
    config
    (catch java.io.FileNotFoundException _)
    (catch java.lang.RuntimeException _))) ;; clojure 1.3 wraps the FileNotFound

(defn- home-dir
  "Returns full path to Pallet home dir ($PALLET_HOME or $HOME/.pallet)"
  []
  (.getAbsolutePath
   (doto (if-let [pallet-home (System/getenv "PALLET_HOME")]
           (java.io.File. pallet-home)
           (java.io.File. (System/getProperty "user.home") ".pallet"))
     .mkdirs)))

(defn ^java.io.File config-file-path
  "Returns a java.io.File for the user's config.clj file"
  []
  (java-io/file (home-dir) "config.clj"))

(defn pallet-config
  "Read pallet configuration from config.clj and services/*.clj files. The
   files are taken from ~/.pallet or $PALLET_HOME if set."
  []
  (reduce
   (fn [config service]
     (assoc-in config [:services (key (first service))] (val (first service))))
   (read-config (.getAbsolutePath (config-file-path)))
   (for [file (filter
               #(and (.isFile %) (.endsWith (.getName %) ".clj"))
               (file-seq (java-io/file (home-dir) "services")))]
     (read-string (slurp file)))))

(defn- check-deprecations
  "Provide deprecation warnings."
  [config]
  (when (:providers config)
    (deprecate/warn
     (str
      "Use of :providers key in ~/.pallet/config.clj is "
      "deprecated. Please change to use :services."))))

(defn- cake-project-environment
  "Read an environment from the cake-project if it exists"
  []
  (-?> 'cake/*project* resolve var-get :environment))

;;; Compute service

(defn compute-service-properties
  "Helper to read compute service properties. Given a config file return the
   selected service definition as a map."
  [config profiles]
  (when config
    (let [service (first profiles)
          default-service (map config [:provider :identity :credential])
          services (:services config (:providers config))
          environment (when-let [env (:environment config)]
                        (environment/eval-environment env))]
      (cond
       ;; no specific service requested, and default service specified by
       ;; top level keys in defppallet
       (and
        (not service)
        (every? identity default-service)) (->
                                            (select-keys
                                             config
                                             [:provider :identity :credential
                                              :blobstore :endpoint
                                              :environment])
                                            (utils/maybe-update-in
                                             [:environment]
                                             (fn [env] environment)))
        ;; pick from specified services
       (map? services) (->
                        (or
                         (and service
                              ;; ensure that if services is specified as a
                              ;; vector of keyword value vectors, that
                              ;; it is converted into a map first.
                              (let [services (into {} services)]
                                (or
                                 (services (keyword service))
                                 (services service))))
                         (and (not service) ; use default if service unspecified
                              (when-let [service (first services)]
                                (-> service second))))
                        ;; merge any top level environment with the service
                        ;; specific environment
                        (utils/maybe-update-in
                         [:environment]
                         #(environment/merge-environments
                           environment
                           (environment/eval-environment
                            (cake-project-environment))
                           (environment/eval-environment %))))
       :else nil))))

(defn compute-service-from-map
  "Create a compute service from a credentials map.
   Uses the :provider, :identity, :credential, :extensions and :node-list keys.
   The :extensions and :node-list keys will be read with read-string if they
   are strings."
  [credentials]
  (let [options (->
                 credentials
                 (update-in [:extensions]
                            #(if (string? %)
                               (map read-string (string/split % #" "))
                               %))
                 (update-in [:node-list] #(if (string? %) (read-string %) %))
                 (update-in [:environment] #(environment/eval-environment %)))]
    (when-let [provider (:provider options)]
      (apply
       compute/compute-service
       provider
       (apply concat (filter second (dissoc options :provider)))))))

(defn compute-service-from-config
  "Compute service from a defpallet configuration map and a list of active
   profiles (provider keys)."
  [config profiles]
  (check-deprecations config)
  (compute-service-from-map
   (compute-service-properties config profiles)))

(defn compute-service-from-config-var
  "Checks to see if pallet.config/service is a var, and if so returns its
  value."
  []
  (utils/find-var-with-require 'pallet.config 'service))

(defn compute-service-from-property
  "If the pallet.config.service property is defined, and refers to a var, then
   return its value."
  []
  (when-let [property (System/getProperty "pallet.config.service")]
    (when-let [sym-names (and (re-find #"/" property)
                              (string/split property #"/"))]
      (utils/find-var-with-require
       (symbol (first sym-names)) (symbol (second sym-names))))))

(defn compute-service-from-config-file
  "Compute service from ~/.pallet/config.clj. Profiles is a sequence of service
   keys to use from the :services map."
  [& profiles]
  (compute-service-from-config (pallet-config) profiles))

(defn compute-service
  "Instantiate a compute service.

   If passed no arguments, then the compute service is looked up in the
   following order:
   - from a var referenced by the pallet.config.service system property
   - from pallet.config/service if defined
   - the first service in config.clj
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
      (compute-service-from-config-file)))
  ([service-name]
     (or
      (compute-service-from-config-file service-name)))
  ([provider-name
    & {:keys [identity credential extensions node-list endpoint environment]
       :as options}]
     (apply compute/compute-service provider-name (apply concat options))))

;;; Blobstore

(def ^{:doc "Translate compute provider to associated blobstore provider"}
  blobstore-lookup
  {"cloudservers" "cloudfiles"
   "cloudservers-us" "cloudfiles-us"
   "cloudservers-eu" "cloudfiles-eu"
   "ec2" "s3"
   "aws-ec2" "aws-s3"})

(defn blobstore-from-map
  "Create a blobstore service from a credentials map.
   Uses :provider, :identity, :credential and
   :blobstore-provider, :blobstore-identity and :blobstore-credential.
   Blobstore keys fall back to the compute keys"
  [credentials]
  (when-let [provider (or (:blobstore-provider credentials)
                          (blobstore-lookup (:provider credentials)))]
    (blobstore/service
     provider
     :identity (or (:blobstore-identity credentials)
                   (:identity credentials))
     :credential (or (:blobstore-credential credentials)
                     (:credential credentials)))))

(defn blobstore-from-config
  "Create a blobstore service form a configuration map."
  [config profiles]
  (let [config (compute-service-properties config profiles)
        {:keys [provider identity credential]} (merge
                                                (update-in
                                                 config [:provider]
                                                 (fn [p]
                                                   (blobstore-lookup p)))
                                                (:blobstore config))]
    (when provider
      (blobstore/service provider :identity identity :credential credential))))

(defn blobstore-service-from-config-var
  "Checks to see if pallet.config/service is a var, and if so returns its
  value."
  []
  (utils/find-var-with-require 'pallet.config 'blobstore-service))

(defn blobstore-service-from-property
  "If the pallet.config.service property is defined, and refers to a var, then
   return its value."
  []
  (when-let [property (System/getProperty "pallet.config.blobstore-service")]
    (when-let [sym-names (and (re-find #"/" property)
                              (string/split property #"/"))]
      (utils/find-var-with-require
       (symbol (first sym-names)) (symbol (second sym-names))))))

(defn blobstore-service-from-config-file
  "Create a blobstore service form a configuration map."
  [& profiles]
  (blobstore-from-config (pallet-config) profiles))

(defn blobstore-service
  "Instantiate a blobstore service.

   If passed no arguments, then the blobstore service is looked up in the
   following order:
   - from a var referenced by pallet.config.blobstore-service system property
   - from pallet.config/blobstore-service if defined
   - the first service in config.clj
   - the service from the first active profile in settings.xml

   If passed a service name, it is looked up in external
   configuration (~/.pallet/config.clj or ~/.m2/settings.xml). A service name is
   one of the keys in the :services map in config.clj, or a profile id in
   settings.xml.

   When passed a provider name and credentials, the service is instantiated
   based on the credentials.  The provider name should be a recognised provider
   name (see `pallet.blobstore/supported-providers` to obtain a list of these).

   The other arguments are keyword value pairs.
   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values."
  ([]
     (or
      (blobstore-service-from-property)
      (blobstore-service-from-config-var)
      (blobstore-service-from-config-file)))
  ([service-name]
     (or
      (blobstore-service-from-config-file service-name)))
  ([provider-name
    & {:keys [identity credential extensions node-list endpoint environment]
       :as options}]
     (apply blobstore/service provider-name (apply concat options))))

;;; Admin user

(defn admin-user-from-property
  "If the pallet.config.admin-user property is defined, and refers to a var
   then return its value."
  []
  (when-let [property (System/getProperty "pallet.config.admin-user")]
    (when-let [sym-names (and (re-find #"/" property)
                              (string/split property #"/"))]
      (utils/find-var-with-require
       (symbol (first sym-names)) (symbol (second sym-names))))))

(defn admin-user-from-config-var
  "Set the admin user based on pallet.config setup."
  []
  (utils/find-var-with-require 'pallet.config 'admin-user))

(defn admin-user-from-config
  "Set the admin user based on a config map"
  [config]
  (when-let [admin-user (:admin-user config)]
    (apply utils/make-user (:username admin-user) (apply concat admin-user))))

(defn admin-user-from-config-file
  "Create an admin user form a configuration map."
  []
  (admin-user-from-config (pallet-config)))

(defn admin-user
  "Instantiate an admin-user.

   If passed no arguments, then the blobstore service is looked up in the
   following order:
   - from a var referenced by pallet.config.admin-user system property
   - from pallet.config/admin-user if defined
   - the :admin-user top level key in config.clj

   Service specific admin-user values should be specified through a :user
   key on the :environment for the service.

   The other arguments are keyword value pairs.
   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values."
  ([]
     (or
      (admin-user-from-property)
      (admin-user-from-config-var)
      (admin-user-from-config-file))))

;;; Service map

(defn service-map
  "Instantiate service objects. The service objects are returned in a map
   with keys as expected by `configure` or `lift`."
  ([]
     {:compute (compute-service)
      :blobstore (blobstore-service)
      :user (admin-user)})
  ([service-name]
     {:compute (compute-service service-name)
      :blobstore (blobstore-service service-name)
      :user (admin-user)}))
