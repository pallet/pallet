(ns pallet.task.containers
  "List contianers."
  (:require
   [pallet.blobstore :as blobstore]))

(defn containers
  "List containers."
  [request & args]
  (doseq [container (blobstore/containers (:blobstore request))
          :let [container (bean container)
                location (-> container :location)]]
    (println
     (format
      "\t%20s  %s"
      (:name container) (.getDescription location)))))
