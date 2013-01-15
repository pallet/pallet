(ns pallet.task.add-service
  "Add a service definition to pallet."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.compute :as compute])
  (:use
   [pallet.configure :only [config-file-path]]
   [pallet.task.config :only [write-config-clj-unless-exists]]))

(defn warn-on-invalid-provider-name
  [provider-name available-services]
  (if (not (and provider-name (some #(= provider-name %) available-services)))
    (do
      (println "WARNING:" provider-name "is not an available provider")
      (println "         Currently loaded providers are:")
      (doseq [provider available-services]
        (println "           " provider))
      (println "Try adding " (str "org.jclouds/" provider-name)
               "or org.jclouds/jclouds-all as a dependency if you can not see"
               "the provider you want. Writing configuration file with"
               "specified provider anyway."))))

(defn write-service
  [file service-name provider-name identity credential properties]
  (.. (java.io.File. (.getParent file)) mkdirs)
  (spit file (pr-str {(keyword service-name)
                      (merge
                       (into {}
                             (filter val {:provider provider-name
                                          :identity identity
                                          :credential credential}))
                       properties)})))

(defn add-service*
  [file service-name provider-name identity credential properties]
  (let [service-name (name service-name)
        available-services (compute/supported-providers)]
    (warn-on-invalid-provider-name provider-name available-services)
    (write-service
     file service-name provider-name identity credential properties)))

(defn usage []
  (binding [*out* *err*]
    (println "incorrect arguments:")
    (println
     (str "  lein pallet add-service service-name provider-name "
          "[identity credential [property-name value ...]]"))))

(defn
  ^{:no-service-required true}
  add-service
  "Add a service provider definition to your pallet configuration.
This will create ~/.pallet/services/service-name.clj"
  ([service-name]
     (add-service service-name service-name))
  ([service-name provider-name]
     (add-service service-name provider-name nil nil))
  ([service-name provider-name identity credential & kw-vals]
     (write-config-clj-unless-exists)
     (if (and service-name provider-name (even? (count kw-vals)))
       (let [service-name (name service-name)
             path (io/file
                   (.getParent
                    (config-file-path)) "services" (str service-name ".clj"))]
         (if (.exists path)
           (do
             (println
              "Service configuration file" (.getPath path) "already exists")
             1)
           (add-service*
            path service-name
            (name provider-name)
            (and identity (name identity))
            (and credential (name credential))
            (into {}
                  (map
                   #(vector (keyword (name (first %))) (second %))
                   (partition 2 kw-vals))))))
       (usage))))
