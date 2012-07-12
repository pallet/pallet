(ns pallet.task.providers
  "Provide information on the supported and enabled providers."
  (:use
   [pallet.compute :only [supported-providers]]))

(defn providers
  "Provide information on the supported and enabled providers."
  {:no-service-required true}
  [& _]
  (println "Pallet uses jcloud's providers.\n")
  (doseq [name (supported-providers)]
    (println (str "\t" name )))
  (println "\nProviders can be enabled by adding a dependency on the jclouds ")
  (println "provider into your project.clj or pom.xml."))
