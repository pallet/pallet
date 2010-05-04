(ns pallet.crate.maven
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-directory :as remote-directory]))

(def maven-parameters
 {:maven-home "/opt/maven2"
  :version "2.2.2"})

(defn maven-download-md5
  [version]
  {"2.2.1" "c581a15cb0001d9b771ad6df7c8156f8"})

(defn maven-download-url
  [version]
  (str "http://www.ibiblio.org/pub/mirrors/apache/maven/binaries/apache-maven-"
       version "-bin.tar.bz2"))

(defn download*
  [& options]
  (let [options (merge maven-parameters (apply hash-map options))]
    (remote-directory/remote-directory*
     (options :maven-home)
     :url (maven-download-url (options :version))
     :md5 (maven-download-md5 (options :version))
     :unpack :tar :tar-options "xj")))

(resource/defresource download
  "Download maven"
  download* [& options])
