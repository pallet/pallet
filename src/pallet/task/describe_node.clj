(ns pallet.task.describe-node
  "Display the node definition for the given node-types"
  (:require
   [pallet.core :as core]
   [clojure.tools.logging :as logging]))

(defn describe-node
  "Display the node definition for the given node-types."
  {:no-service-required true}
  [& args]
  (doseq [arg args]
    (println (format "%s\t %s" (arg :tag) (arg :image)))))
