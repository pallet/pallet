(ns pallet.task.images
  "List images."
  (:require
   [pallet.compute :as compute]))

(defn images
  "List available images."
  [request & args]
  (doseq [image (compute/images (:compute request))
          :let [image (bean image)]]
    (println (pr-str image))))
