(ns pallet.blobstore
  "Blobstore abstraction"
  (:require
   [pallet.blobstore.implementation :as implementation]
   [pallet.utils :as utils]))

;;; Blobstore service instantiation
(defn service
  "Instantiate a blobstore service based on the given arguments"
  [provider-name
   & {:keys [identity credential extensions] :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

(defprotocol Blobstore
  (sign-blob-request
   [blobstore container path request-map]
   "Create a signed request")
  (put
   [blobstore container path payload]
   "Upload a file, string, input stream, etc")
  (put-file
   [blobstore container path file]
   "Upload a file")
  (containers
   [blobstore]
   "List containers")
  (close
   [blobstore]
   "Close the blobstore"))

(defn blobstore?
  "Predicate to test if argument is a blobstore."
  [b]
  (satisfies? Blobstore b))

;;; Add deprecated forwarding functions
;;;  blobstore-from-map
;;;  blobstore-from-config
;;;  blobstore-from-config-file

(utils/fwd-to-configure blobstore-from-map)
(utils/fwd-to-configure blobstore-from-config)
(utils/fwd-to-configure blobstore-from-config-file)
