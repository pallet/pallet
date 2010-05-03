(ns server.ci
  (:require
   [pallet.resource.package :as package]
   [pallet.core :as core]
   [pallet.crate.git :as git]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.resource.service :as service]
   [pallet.resource.user :as user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.tomcat :as tomcat]
   [pallet.crate.hudson :as hudson]))

(defn ci-config
  []
  (package/package "maven2")
  (git/git)
  (tomcat/tomcat)
  (user/user "testuser" :create-home true :shell :bash)
  (service/with-restart "tomcat6"
    (tomcat/server-configuration (tomcat/server))
    (user/user (hudson/hudson-user-name) :comment "\"hudson,,,\"")
    (ssh-key/generate-key (hudson/hudson-user-name))
    (ssh-key/authorize-key-for-localhost
     (hudson/hudson-user-name) "id_rsa.pub"
     :authorize-for-user "testuser")
    (hudson/tomcat-deploy :version "1.355")
    (hudson/plugin :git)
    (hudson/maven "default maven" "2.2.1")
    (hudson/job :maven2 "pallet"
                :maven-name "default maven"
                :goals "-Ptestuser clean test"
                :group-id "pallet"
                :artifact-id "pallet"
                :maven-opts ""
                :scm ["git://github.com/hugoduncan/pallet.git"])
    (hudson/job :maven2 "pallet-clojure-1.2"
                :maven-name "default maven"
                :goals "-Ptestuser -Pclojure-1.2 clean test"
                :group-id "pallet"
                :artifact-id "pallet"
                :maven-opts ""
                :scm ["git://github.com/hugoduncan/pallet.git"])
    (hudson/job :maven2 "clj-ssh"
                :maven-name "default maven"
                :goals "-Ptestuser clean test"
                :group-id "clj-ssh"
                :artifact-id "clj-ssh"
                :maven-opts ""
                :scm ["git://github.com/hugoduncan/clj-ssh.git"])))


(defn remove-ci
  []
  (hudson/tomcat-undeploy)
  (package/package "maven2" :action :remove :purge true)
  (user/user "testuser" :action :remove)
  (tomcat/tomcat :action :remove :purge true))

(core/defnode ci
  [:ubuntu :X86_64 :smallest
   :image-name-matches ".*"
   :os-description-matches "[^J]+9.10[^32]+"]
  :bootstrap [(automated-admin-user/automated-admin-user)
              (package/package-manager :update)]
  :configure [(ci-config)])
