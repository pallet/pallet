(ns pallet.crate.maven
  (:require
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.resource.remote-directory :as remote-directory])
  (:use
   pallet.thread-expr))

(def maven-parameters
 {:maven-home "/opt/maven2"
  :version "2.2.2"})

(defn maven-download-md5
  [version]
  {"2.2.1" "c581a15cb0001d9b771ad6df7c8156f8"})

(defn maven-download-url
  [version]
  (str "http://mirrors.ibiblio.org/pub/mirrors/apache/maven/binaries/apache-maven-"
       version "-bin.tar.bz2"))

(defn download
  [request & {:keys [maven-home version]
              :or {maven-home "/opt/maven2" version "2.2.2"}
              :as options}]
  (remote-directory/remote-directory
   request
   maven-home
   :url (maven-download-url version)
   :md5 (maven-download-md5 version)
   :unpack :tar :tar-options "xj"))

(defn package
  [request]
  (->
   request
   (when->
    (= :amzn-linux (request-map/os-family request))
    (package/add-jpackage :releasever "5.0"))
   (package/package "maven2")))
