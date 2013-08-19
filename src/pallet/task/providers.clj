(ns pallet.task.providers
  "Provide information on the supported and enabled providers."
  (:require
   [pallet.compute :refer [supported-providers]]))

(defn providers
  "Provide information on the supported and enabled providers."
  {:no-service-required true}
  [& _]
  (println "Pallet uses its own and jcloud's providers.\n")
  (doseq [name (supported-providers)]
    (println (format "  %s" name)))
  (println "\nProviders can be enabled by adding a dependency on a pallet or\n")
  (println "jclouds provider into your project.clj or pom.xml."))
