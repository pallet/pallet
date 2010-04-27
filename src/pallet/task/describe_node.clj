(ns pallet.task.describe-node
  "Adjust node counts."
  (:require
   [pallet.core :as core]
   [clojure.contrib.logging :as logging]))

(defn describe-node
  "Display the node defintion for the given node-types."
  [& args]
  (doseq [arg args]
    (println (format "%s\t %s" (arg :tag) (arg :image)))))
