(ns server.pallet
  (:require
   [pallet.resource.package :as package]
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.crate.automated-admin-user :as automated-admin-user]

   [pallet.crate.git :as git]
   [pallet.crate.java :as java]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.resource.user :as user]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.hostinfo :as hostinfo]))


(core/defnode devenv
  [:ubuntu :X86_64 :smallest
   :image-name-matches ".*"
   :os-description-matches "[^J]+10.04[^32]+"]
  :bootstrap [(automated-admin-user/automated-admin-user)
              (package/package-manager :update)]
  :configure [(git/git)
              (package/package "maven2")
              (java/java :sun :jdk)
              (ssh-key/generate-key (System/getProperty "user.name"))
              (directory/directory
               ".m2"
               :owner (System/getProperty "user.name"))
              (remote-file/remote-file
               ".m2/settings.xml"
               :local-file (str (System/getProperty "user.home") "/.m2/settings.xml")
               :owner (System/getProperty "user.name"))])
