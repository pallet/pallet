(ns pallet.crate.gpg
  "Install gpg"
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.user :as user]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script])
  (:use
   pallet.thread-expr))

(defn gpg
  "Install from packages"
  [request]
  (package/package request "pgpgpg"))

(defn import-key
  "Import key. Content options are as for remote-file."
  [request & {:keys [user] :as options}]
  (let [path "gpg-key-import"
        user (or user (-> request :user :username))
        home (stevedore/script (user-home ~user))
        dir (str home "/.gnupg")]
    (->
     request
     (directory/directory dir :mode "0700" :owner user)
     (apply->
      remote-file/remote-file
      path (apply concat (merge {:mode "0600" :owner user} options)))
     (exec-script/exec-checked-script
      "Import gpg key"
      (sudo -u ~user gpg -v -v "--homedir" ~dir "--import" ~path))
     (remote-file/remote-file path :action :delete :force true))))
