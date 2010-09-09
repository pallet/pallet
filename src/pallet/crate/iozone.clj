(ns pallet.crate.iozone
  "Crate for iozone disk benchmark"
  (:require
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.template :as template]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.resource.directory :as directory])
  (:use
   pallet.thread-expr))

(def src-packages
     ["gcc"])

(def iozone-md5s
     {"3_347" "db136f775b19e6d9c00714bd2399785f"})

(defn ftp-path [version]
  (format "http://iozone.org/src/current/iozone%s.tar" version))

(def iozone-conf-dir "/etc/iozone")
(def iozone-install-dir "/opt/iozone")
(def iozone-log-dir "/var/log/iozone")

(defn iozone
  "Install iozone from source. Options:
     :version version-string   -- specify the version (default \"3_347\")"
  [request & {:keys [version] :or {version "3_347"} :as options}]
  (let [basename (str "iozone-" version)
        tarfile (str basename ".tar")
        tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
    (->
     request
     (for-> [p src-packages]
       (package/package p))
     (remote-file/remote-file
      tarpath :url (ftp-path version) :md5 (get iozone-md5s version "x"))
     (directory/directory iozone-install-dir :owner "root")
     (exec-script/exec-checked-script
      "Build iozone"
      (cd ~iozone-install-dir)
      (tar x --strip-components=1 -f ~tarpath)
      (cd ~(str iozone-install-dir "/src/current"))
      (make "linux"))                   ; cludge
     (remote-file/remote-file
      "/usr/local/bin/iozone"
      :remote-file (str iozone-install-dir "/src/current/iozone")
      :owner "root" :group "root" :mode "0755"))))
