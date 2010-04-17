(ns pallet-server
  (:use
   [pallet.resource.package :only [package]]
   [pallet.crate.git :only [git]])
  (:require
   [pallet.resource.service :as service]
   [pallet.resource.user :as user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.tomcat :as tomcat]
   [pallet.crate.hudson :as hudson]))

(defn ci
  []
  (package "maven2")
  (git)
  (tomcat/tomcat)
  (service/with-restart "tomcat6"
    (tomcat/server-configuration (tomcat/server))
    (user/user (hudson/hudson-user-name) :comment "\"hudson,,,\"")
    (ssh-key/generate-key (hudson/hudson-user-name))
    (ssh-key/authorize-key-for-localhost (hudson/hudson-user-name) "id_rsa.pub")
    (hudson/tomcat-deploy)
    (hudson/plugin :git)
    (hudson/maven "default maven" "2.2.1")
    (hudson/job :maven2 "pallet"
                :maven-name "default maven"
                :goals "test"
                :group-id "pallet"
                :artifact-id "pallet"
                :scm ["http://github.com/hugoduncan/pallet.git"])))

