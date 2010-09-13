(ns pallet.crate.node-js
  "Install and configure node.js"
  (:require
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.remote-directory :as remote-directory]))

(def src-dir "/opt/local/node-js")

(def md5s {"0.2.1" nil})

(defn tarfile
  [version]
  (format "node-v%s.tar.gz" version))

(defn download-path [version]
  (format "http://nodejs.org/dist/%s" (tarfile version)))

(defn install
  [request & {:keys [version] :or {version "0.2.1"}}]
  (->
   request
   (package/packages
    :yum ["gcc" "glib" "glibc-common" "python"]
    :aptitude ["build-essential" "python" "libssl-dev"])
   (remote-directory/remote-directory
    src-dir :url (download-path version) :md5 (md5s version) :unpack :tar)
   (exec-script/exec-checked-script
    "Build node-js"
    (cd ~src-dir)
    ("./configure")
    (make)
    (make install))))
