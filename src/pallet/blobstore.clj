(ns pallet.blobstore
  "Blobstore abstraction"
  (:require
   [pallet.blobstore.implementation :as implementation]
   [pallet.configure :as configure]))

;;; Compute Service instantiation
(defn service
  "Instantiate a blobstore service based on the given arguments"
  [provider-name
   & {:keys [identity credential extensions] :as options}]
  (implementation/load-providers)
  (implementation/service provider-name options))

(defn blobstore-from-settings
  "Create a blobstore service from propery settings."
  [& profiles]
  (try
    (require 'pallet.maven) ; allow running without maven jars
    (let [f (ns-resolve 'pallet.maven 'credentials)
          credentials (f profiles)]
      (when-let [provider (:blobstore-provider credentials)]
        (service
         provider
         :identity (:blobstore-identity credentials)
         :credential (:blobstore-credential credentials))))
    (catch ClassNotFoundException _)
    (catch clojure.lang.Compiler$CompilerException _)))

(defn blobstore-from-config
  [config profiles]
  (let [config (configure/compute-service-properties config profiles)
        {:keys [provider identity credential]} (merge config
                                                      (:blobstore config))]
    (when provider
      (service provider identity credential))))


(defprotocol Blobstore
  (sign-blob-request
   [blobstore container path request-map]
   "Create a signed request")
  (put-file
   [blobstore container path file]
   "Upload a file")
  (containers
   [blobstore]
   "List containers")
  (close
   [blobstore]
   "Close the blobstore"))
