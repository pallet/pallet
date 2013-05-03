(ns pallet.task.describe-node
  "Adjust node counts."
  (:require
   [clojure.pprint :refer [pprint]]
   [pallet.task :refer [maybe-resolve-symbol-string]]))

(defn describe-node
  "Display the node definition for the given node-types."
  {:no-service-required true}
  [& args]
  (doseq [arg (map maybe-resolve-symbol-string args)]
    (pprint arg)))
