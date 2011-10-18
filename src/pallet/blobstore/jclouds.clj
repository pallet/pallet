(ns pallet.blobstore.jclouds
  "jclouds blobstore implementation"
  (:require
   [org.jclouds.blobstore :as jclouds-blobstore]
   [org.jclouds.blobstore2 :as jclouds-blobstore2]
   pallet.blobstore
   [pallet.blobstore.implementation :as implementation]
   [pallet.compute.jvm :as jvm]))

(defn default-jclouds-extensions
  "Default extensions"
  []
  (if (jvm/log4j?)
    [:log4j]
    []))

(defmethod implementation/service :default
  [provider {:keys [identity credential extensions]
             :or {identity ""
                  credential ""
                  extensions (default-jclouds-extensions)}}]
  (apply jclouds-blobstore/blobstore
         provider identity credential extensions))

(extend-type org.jclouds.blobstore.BlobStore
  pallet.blobstore/Blobstore

  (sign-blob-request [blobstore container path request-map]
    (let [request (jclouds-blobstore/sign-blob-request
                   container path request-map blobstore)]
      {:endpoint (.getEndpoint request)
       :headers (.. request getHeaders entries)}))

  (put-file [blobstore container path file]
    (when-not (jclouds-blobstore/container-exists? container blobstore)
      (jclouds-blobstore/create-container container nil blobstore))
    (jclouds-blobstore2/put-blob
     blobstore container
     (jclouds-blobstore2/blob path :payload (java.io.File. file))
     :multipart? true))

  (put [blobstore container path payload]
    (when-not (jclouds-blobstore/container-exists? container blobstore)
      (jclouds-blobstore/create-container container nil blobstore))
    (jclouds-blobstore2/put-blob
     blobstore container
     (jclouds-blobstore2/blob path :payload payload)
     :multipart? false))

  (containers [blobstore] (jclouds-blobstore/containers blobstore))

  (close [blobstore] (.. blobstore getContext close)))
