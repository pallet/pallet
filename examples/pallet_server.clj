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
  (user/user "testuser" :create-home true :shell :bash)
  (service/with-restart "tomcat6"
    (tomcat/server-configuration (tomcat/server))
    (user/user (hudson/hudson-user-name) :comment "\"hudson,,,\"")
    (ssh-key/generate-key (hudson/hudson-user-name))
    (ssh-key/authorize-key-for-localhost
     (hudson/hudson-user-name) "id_rsa.pub"
     :authorize-for-user "testuser")
    (hudson/tomcat-deploy)
    (hudson/plugin :git)
    (hudson/maven "default maven" "2.2.1")
    (hudson/job :maven2 "pallet"
                :maven-name "default maven"
                :goals "test"
                :group-id "pallet"
                :artifact-id "pallet"
                :maven-opts "-Ptestuser"
                :scm ["http://github.com/hugoduncan/pallet.git"])
    (hudson/job :maven2 "clj-ssh"
                :maven-name "default maven"
                :goals "test"
                :group-id "clj-ssh"
                :artifact-id "clj-ssh"
                :maven-opts "-Ptestuser"
                :scm ["http://github.com/hugoduncan/clj-ssh.git"])))

