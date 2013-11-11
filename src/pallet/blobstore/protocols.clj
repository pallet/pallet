(ns pallet.blobstore.protocols
  "Protocols for the blobstore"
  (:require
   [clojure.core.typed :refer [defprotocol>]]))

;;; # Blobstore
(defprotocol> Blobstore
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
