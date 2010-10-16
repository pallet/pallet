(ns pallet.blobstore
  "Blobstore abstraction"
  (:require
   pallet.maven))

;;; Compute Service instantiation
(defmulti service
  "Instantiate a blobstore service based on the given arguments"
  (fn [first & _] (keyword first)))

(defn blobstore-from-settings
  "Create a blobstore service from propery settings."
  []
  (let [credentials (pallet.maven/credentials)]
    (service
     (:blobstore-provider credentials)
     :identity (:blobstore-identity credentials)
     :credential (:blobstore-credential credentials))))



(defprotocol Blobstore
  (sign-blob-request
   [blobstore container path request-map]
   "Create a signed request"))
