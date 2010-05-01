(ns pallet.main-invoker
  "Invoke tasks requiring a compute service.  This decouples main from anything
   pallet, jclouds or maven specific, and ensures compiling main doesn't compile
   the world."
  (:require
   [org.jclouds.compute :as jclouds]
   [pallet.maven :as maven]
   [pallet.main :as main]))

(def default-service-opts [:log4j :enterprise :ssh])

(defn invoke
  [service user key task params]
  (let [[service user key] (if service
                             [service user key]
                             (maven/credentials))]
    (if service
      (let [compute (apply jclouds/compute-service
                           (concat [service user key] default-service-opts))]
        (jclouds/with-compute-service [compute]
          (apply task params)))
      (do
        (println "Error: no credentials supplied\n\n")
        (apply (main/resolve-task "help") [])))))
