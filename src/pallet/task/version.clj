(ns pallet.task.version
  "Print the pallet version"
  (:require
   [pallet.core :as core]))

(defn version
  "Print the pallet version"
  {:no-service-required true}
  []
  (println "Pallet" (core/version)
           "on Java" (System/getProperty "java.version")
           (System/getProperty "java.vm.name")))
