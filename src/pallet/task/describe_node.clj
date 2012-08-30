(ns pallet.task.describe-node
  "Display the node definition for the given node-types"
  (:require
   [pallet.core :as core]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.task :only [maybe-resolve-symbol-string]]
   [clojure.pprint :only [pprint]]))

(defn describe-node
  "Display the node definition for the given node-types."
  {:no-service-required true}
  [& args]
  (doseq [arg (map maybe-resolve-symbol-string args)]
    (pprint arg)))
