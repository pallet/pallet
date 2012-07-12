(ns pallet.task.containers
  "List blobstore contianers."
  (:require
   [pallet.blobstore :as blobstore]))

(defn containers
  "List blobstore containers."
  [request & args]
  (doseq [container (blobstore/containers (:blobstore request))
          :let [container (bean container)
                location (-> container :location)]]
    (println
     (format
      "\t%20s  %s"
      (:name container) (.getDescription location)))))
