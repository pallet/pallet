(ns pallet.task.providers
  "Provide information on the supported and enabled providers."
  (:use
   [pallet.compute :only [supported-providers]]))

(defn providers
  "Provide information on the supported and enabled providers."
  {:no-service-required true}
  [& _]
  (println "Pallet uses it's own and jcloud's providers.\n")
  (println "The following providers are available with the\n")
  (println "current project dependencies.\n")
  (doseq [name (supported-providers)]
    (println (format "  %s" name)))
  (println "\nProviders can be enabled by adding a dependency on a pallet or\n")
  (println "jclouds provider into your project.clj or pom.xml."))
