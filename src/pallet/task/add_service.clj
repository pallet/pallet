(ns pallet.task.add-service
  "Add a service definition to pallet.

   This doesn't work, see:
   http://stackoverflow.com/questions/3790889/clojure-lein-read-line-stdin-woes"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.compute :as compute]))

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
  [file service-name provider-name identity credential]
  (.. (java.io.File. (.getParent file)) mkdirs)
  (spit file (pr-str {(keyword service-name) {:provider provider-name
                                              :identity identity
                                              :credential credential}})))

(defn add-service*
  [file service-name provider-name identity credential]
  (let [service-name (name service-name)
        available-services (compute/supported-providers)]
    (warn-on-invalid-provider-name provider-name available-services)
    (write-service file service-name provider-name identity credential)))

(defn usage []
  (binding [*out* *err*]
    (println "incorrect arguments:")
    (println "  lein pallet service-name provider-name identity credential")))

(defn
  ^{:no-service-required true}
  add-service
  "Add a service provider definition to your pallet configuration.
       lein pallet add-serivce name [provider [identity [credential]]]
   This will create ~/.pallet/services/name.clj"
  [ & [service-name provider-name identity credential & _]]
  (if (and service-name provider-name identity credential)
    (let [service-name (name service-name)
          path (io/file
                (System/getProperty "user.home")
                ".pallet" "services" service-name)]
      (if (.exists path)
        (do
          (println
           "Service configuration file" (.getPath path) "already exists")
          1)
        (add-service*
         path service-name
         (name provider-name)
         (name identity)
         (name credential))))
    (usage)))
