(ns pallet.main-invoker
  "Invoke tasks requiring a compute service.  This decouples main from anything
   pallet, jclouds or maven specific, and ensures compiling main doesn't compile
   the world."
  (:require
   [clojure.contrib.logging :as logging]
   [clojure.java.io :as java-io]
   [clojure.walk :as walk]
   [pallet.compute :as compute]
   [pallet.blobstore :as blobstore]
   [pallet.utils :as utils]
   [pallet.main :as main]))

(defn log-info
  []
  (logging/debug (format "OS              %s %s"
                         (System/getProperty "os.name")
                         (System/getProperty "os.version")))
  (logging/debug (format "Arch            %s" (System/getProperty "os.arch")))
  (logging/debug (format "Admin user      %s" (:username utils/*admin-user*)))
  (let [private-key-path (:private-key-path utils/*admin-user*)
        public-key-path (:public-key-path utils/*admin-user*)]
    (logging/debug
     (format "private-key-path %s %s" private-key-path
             (.canRead (java.io.File. private-key-path))))
    (logging/debug
     (format "public-key-path %s %s" public-key-path
             (.canRead (java.io.File. public-key-path))))))

(defn compute-service-properties
  [config profiles]
  (when config
    (let [provider (first profiles)
          default-provider (map config [:provider :identity :credential])
          providers (:providers config)]
      (cond
       (every? identity default-provider) {:provider (:provider config)
                                           :identity (:identity config)
                                           :credential (:credential config)
                                           :blobstore (:blobstore config)}
       (map? providers) (or
                         (and provider (or
                                        (providers (keyword provider))
                                        (providers provider)))
                         (and (not provider) ; use default if no profile
                                             ; requested
                              (first providers)
                              (-> providers first val)))
       :else nil))))

(defn compute-service-from-config
  [config profiles]
  (let [{:keys [provider identity credential]} (compute-service-properties
                                                config profiles)]
    (when provider
      (compute/compute-service
       provider :identity identity :credential credential))))

(defn blobstore-from-config
  [config profiles]
  (let [config (compute-service-properties config profiles)
        {:keys [provider identity credential]} (merge config
                                                      (:blobstore config))]
    (when provider
      (blobstore/service provider identity credential))))

(defn find-compute-service
  "Look for a compute service in the following sequence:
     Check pallet.config.service property,
     check maven settings,
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [defaults project profiles]
  (or
   (compute-service-from-config (:pallet project) profiles)
   (compute-service-from-config defaults profiles)
   (compute/compute-service-from-property)
   (apply compute/compute-service-from-settings profiles)))


(defn find-blobstore
  "Look for a compute service in the following sequence:
     Check pallet.config.service property,
     check maven settings,
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [defaults project profiles]
  (or
   (blobstore-from-config (:pallet project) profiles)
   (blobstore-from-config defaults profiles)
   (apply blobstore/blobstore-from-settings profiles)))


(def ^{:private true} config nil)

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
  [& {:keys [provider identity credential providers admin-user]
      :as config-options}]
  `(let [m# (zipmap
             ~(cons 'list (keys config-options))
             ~(cons 'list (unquote-vals (vals config-options))))]
    (alter-var-root
     #'config
     (fn [_#] m#))))

(defn- read-config
  [file]
  (try
    (load-file file)
    config
    (catch java.io.FileNotFoundException _)))

(defn- home-dir
  "Returns full path to Pallet home dir ($PALLET_HOME or $HOME/.pallet)"
  []
  (.getAbsolutePath
   (doto (if-let [pallet-home (System/getenv "PALLET_HOME")]
           (java.io.File. pallet-home)
           (java.io.File. (System/getProperty "user.home") ".pallet"))
     .mkdirs)))

(defn pallet-config
  []
  (read-config (.getAbsolutePath (java-io/file (home-dir) "config.clj"))))

(defn invoke
  [service user key profiles task params project-options]
  (utils/admin-user-from-config)
  (log-info)
  (let [default-config (pallet-config)
        compute (if service
                  (compute/compute-service
                   service :identity user :credential key)
                  (find-compute-service
                   default-config project-options profiles))
        blobstore (if service
                    (blobstore/service
                     service :identity user :credential key)
                    (find-blobstore
                     default-config project-options profiles))]
    (if compute
      (do
        (logging/debug (format "Running as      %s@%s" user service))
        (try
          (apply task {:compute compute
                       :blobstore blobstore
                       :project project-options} params)
          (finally ;; make sure we don't hang on exceptions
           (compute/close compute)
           (when blobstore
             (blobstore/close blobstore)))))
      (do
        (println "Error: no credentials supplied\n\n")
        (apply (main/resolve-task "help") [])))))
