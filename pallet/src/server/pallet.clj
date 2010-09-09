(ns server.pallet
  (:require
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.git :as git]
   [pallet.crate.java :as java]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.resource :as resource]
   [pallet.resource.directory :as directory]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.user :as user]
   [pallet.stevedore :as stevedore]))


(core/defnode devenv
  []
  :bootstrap (resource/phase
              (automated-admin-user/automated-admin-user)
              (package/package-manager :update))
  :configure (resource/phase
              (git/git)
              (package/package "maven2")
              (java/java :sun :jdk)
              (ssh-key/generate-key (System/getProperty "user.name"))
              (directory/directory
               ".m2"
               :owner (System/getProperty "user.name"))
              (remote-file/remote-file
               ".m2/settings.xml"
               :local-file (str (System/getProperty "user.home")
                                "/.m2/settings.xml")
               :owner (System/getProperty "user.name"))))
