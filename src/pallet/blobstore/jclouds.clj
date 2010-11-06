(ns pallet.blobstore.jclouds
  "jclouds blobstore implementation"
  (:require
   [org.jclouds.blobstore :as jclouds-blobstore]
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
  (sign-blob-request
   [blobstore container path request-map]
   (let [request (jclouds-blobstore/sign-blob-request
                  container path request-map blobstore)]
     {:endpoint (.getEndpoint request)
      :headers (.. request getHeaders entries)}))
  (put-file
   [blobstore container path file]
   (when-not (jclouds-blobstore/container-exists? container blobstore)
     (jclouds-blobstore/create-container container nil blobstore))
   (jclouds-blobstore/upload-blob
    container path (java.io.File. file) blobstore))
  (close [blobstore] (.. blobstore getContext close)))
