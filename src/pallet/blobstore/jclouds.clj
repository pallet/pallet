(ns pallet.blobstore.jclouds
  "jclouds blobstore implementation"
  (:require
   [org.jclouds.blobstore :as jclouds-blobstore]
   [pallet.blobstore :as blobstore]
   [pallet.compute.jvm :as jvm]))

(defn default-jclouds-extensions
  "Default extensions"
  []
  (if (jvm/log4j?)
    [:log4j]
    []))

(defmethod blobstore/service :default
  [provider & {:keys [identity credential extensions]
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
      :headers (.. request getHeaders entries)})))
