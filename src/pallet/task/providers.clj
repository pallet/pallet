(ns pallet.task.providers
  "Provide information on the supported and enabled providers."
  (:require
   [pallet.utils :as utils]))

(defn- provider-properties []
  (apply
   hash-map
   (apply concat
          (filter #(re-find #"(.*)\.contextbuilder" (first %))
                  (utils/resource-properties "rest.properties")))))

(defn- enabled?
  [provider]
  (try
   (Class/forName provider)
   (catch java.lang.ClassNotFoundException e)))

(defn providers-from-properties
  []
  (for [supported (sort #(compare (first %1) (first %2))
                          (provider-properties))
          :let [key (first supported)
                name (.substring key 0 (.indexOf key "."))]]
    [name (enabled? (second supported))]))

(defmacro jclouds-providers-fn
  []
  (if (try (Class/forName "org.jclouds.providers.Providers")
           (catch ClassNotFoundException _))
    `(defn jclouds-providers
       []
       (for [provider# (org.jclouds.providers.Providers/viewableAs
                        org.jclouds.compute.ComputeServiceContext)]
         [(.getName provider#) true]))
    `(defn jclouds-providers [] (providers-from-properties))))

(jclouds-providers-fn)

(defn providers
  "Provide information on the supported and enabled providers."
  {:no-service-required true}
  [& _]
  (println "Pallet uses jcloud's providers.\n")
  (doseq [[name enabled] (jclouds-providers)]
    (println (format "\t%38s  %s" name (if enabled "Enabled" "Disabled"))))
  (println "\nProviders can be enabled by adding a dependency on the jclouds ")
  (println "provider into your project.clj or pom.xml."))
