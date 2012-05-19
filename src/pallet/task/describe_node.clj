(ns pallet.task.describe-node
  "Adjust node counts."
  (:require
   [clojure.tools.logging :as logging]))

(defn describe-node
  "Display the node definition for the given node-types."
  {:no-service-required true}
  [& args]
  (doseq [arg args]
    (println (format "%s\t %s" (arg :tag) (arg :image)))))
