(ns pallet.task.config
  "Create a pallet configuration file in ~/.pallet/config.clj"
  (:require
   [clojure.java.io :as io])
  (:use
   [pallet.configure :only [config-file-path]]))

(defn write-config-clj
  [^java.io.File file]
  (.mkdirs (.getParentFile file))
  (spit file
        "(defpallet
  ;; you can specify global data in the :environment key here
  ;; :environment {:proxy \"http://192.168.1.37:3128\"
  ;;               :algorithms
  ;;                {:lift-fn pallet.core/parallel-lift
  ;;                 :converge-fn pallet.core/parallel-adjust-node-counts}}
)"))

(defn write-config-clj-unless-exists
  "Write a config.clj file if one doesn't exist. Returns true if it actually
  writes"
  []
  (let [file (config-file-path)]
    (if (.exists file)
      false
      (do
        (write-config-clj file)
        true))))

(defn ^{:no-service-required true
        :help-arglists '([])}
  config
  "Create a pallet configuration file in ~/.pallet/config.clj"
  [& _]
  (when-not (write-config-clj-unless-exists)
    (println "config file already exists at" (.getPath (config-file-path))))
  0)
