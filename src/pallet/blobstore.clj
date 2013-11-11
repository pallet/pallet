(ns pallet.blobstore
  "Blobstore abstraction"
  (:require
   [pallet.blobstore.implementation :as implementation]
   [pallet.blobstore.protocols :as impl]
   [pallet.utils :as utils]))

;;; Blobstore service instantiation
(defn service
  "Instantiate a blobstore service based on the given arguments"
  [provider-name
   & {:keys [identity credential extensions] :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

(defn sign-blob-request
 "Create a signed request"
 [blobstore container path request-map]
 (impl/sign-blob-request blobstore container path request-map))

(defn put
 "Upload a file, string, input stream, etc"
 [blobstore container path payload]
 (impl/put blobstore container path payload))

(defn put-file
 "Upload a file"
 [blobstore container path file]
 (impl/put-file blobstore container path file))

(defn containers
 "List containers"
 [blobstore]
 (impl/containers blobstore))

(defn close
 "Close the blobstore"
 [blobstore]
 (impl/close blobstore))

(defn blobstore?
  "Predicate to test if argument is a blobstore."
  [b]
  (satisfies? pallet.blobstore.protocols/Blobstore b))

;;; Add deprecated forwarding functions
;;;  blobstore-from-map
;;;  blobstore-from-config
;;;  blobstore-from-config-file

(utils/fwd-to-configure blobstore-from-map)
(utils/fwd-to-configure blobstore-from-config)
(utils/fwd-to-configure blobstore-from-config-file)
