(ns pallet.configure
  "Pallet configuration"
  (:require
   [clojure.java.io :as java-io]
   [clojure.walk :as walk]))

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
  "Read pallet configuration file."
  []
  (read-config (.getAbsolutePath (java-io/file (home-dir) "config.clj"))))

(defn compute-service-properties
  "Helper to read compute service properties"
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
