(ns pallet.blobstore
  (:require
   [org.jclouds.blobstore :as jclouds-blobstore]))

(defn blobstore-from-options
  [current-value {:keys [blobstore blobstore-service]}]
  (or current-value
      blobstore
      (and blobstore-service
           (jclouds-blobstore/blobstore
            (:provider blobstore-service)
            (:identity blobstore-service)
            (:credential blobstore-service)))
      (if (bound? #'jclouds-blobstore/*blobstore*)
        jclouds-blobstore/*blobstore*)))
