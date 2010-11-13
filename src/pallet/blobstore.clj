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

(def ^{:doc "Translate compute provider to associated blobstore provider"}
  blobstore-lookup
  {"cloudservers" "cloudfiles"
   "ec2" "s3"})

(defn blobstore-from-map
  "Create a blobstore service from a credentials map.
   Uses :provider, :identity, :credential and
   :blobstore-provider, :blobstore-identity and :blobstore-credential.
   Blobstore keys fall back to the compute keys"
  [credentials]
  (when-let [provider (or (:blobstore-provider credentials)
                          (blobstore-lookup (:provider credentials)))]
    (service
     provider
     :identity (or (:blobstore-identity credentials)
                   (:identity credentials))
     :credential (or (:blobstore-credential credentials)
                     (:credential credentials)))))

(defn blobstore-from-settings
  "Create a blobstore service from ~/.m2/settings.xml propery settings."
  [& profiles]
  (try
    (require 'pallet.maven) ; allow running without maven jars
    (when-let [f (ns-resolve 'pallet.maven 'credentials)]
      (blobstore-from-map (f profiles)))
    (catch ClassNotFoundException _)
    (catch clojure.lang.Compiler$CompilerException _)))

(defn blobstore-from-config
  "Create a blobstore service form a configuration map."
  [config profiles]
  (let [config (configure/compute-service-properties config profiles)
        {:keys [provider identity credential]} (merge
                                                (update-in
                                                 config [:provider]
                                                 (fn [p]
                                                   (blobstore-lookup p p)))
                                                (:blobstore config))]
    (when provider
      (service provider :identity identity :credential credential))))


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
