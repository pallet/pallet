(ns pallet.crate.zeromq
  (:require
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.crate.iptables :as iptables]))

(def src-path "/opt/local/zeromq")
(def md5s {})

(defn download-url
  "The url for downloading zeromq"
  [version]
  (format
   "http://www.zeromq.org/local--files/area:download/zeromq-%s.tar.gz"
   version))

(defn install
  "Install zeromq from source."
  [request & {:keys [version] :or {version "2.0.9"}}]
  (->
   request
   (package/packages
    :yum ["gcc" "glib" "glibc-common" "uuid-dev"]
    :aptitude ["build-essential" "uuid-dev"])
   (remote-directory/remote-directory
    src-path
    :url (download-url version) :md5 (md5s version) :unpack :tar)
   (exec-script/exec-checked-script
    "Build zeromq"
    (cd ~src-path)
    ("./configure")
    (make)
    (make install)
    (ldconfig))))

(defn iptables-accept
  "Accept zeromq connections, by default on port 5672"
  ([request] (iptables-accept request 5672))
  ([request port]
     (iptables/iptables-accept-port request port)))
