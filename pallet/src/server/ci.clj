(ns server.ci
  (:require
   [pallet.resource.package :as package]
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.crate.git :as git]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.resource.service :as service]
   [pallet.resource.user :as user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.tomcat :as tomcat]
   [pallet.crate.hudson :as hudson]))

(defn generate-ssh-keys
  "Generate key for testing clj-ssh and pallet"
  [request]
  (let [user (parameter/get-for-target request [:hudson :user])]
    (->
     request
     (user/user user :comment "\"hudson,,,\"")
     (ssh-key/generate-key user :comment "pallet test key")
     (ssh-key/generate-key user :file "clj_ssh" :comment "clj-ssh test key")
     (ssh-key/generate-key user :file "clj_ssh_pp" :passphrase "clj-ssh"
                           :comment "clj-ssh test key with passphrase")
     (ssh-key/authorize-key-for-localhost
      user "id_rsa.pub" :authorize-for-user "testuser")
     (ssh-key/authorize-key-for-localhost
      user "clj_ssh.pub" :authorize-for-user "testuser")
     (ssh-key/authorize-key-for-localhost
      user "clj_ssh_pp.pub" :authorize-for-user "testuser"))))

(defn ci-config
  [request]
  (->
   request
   (package/package "maven2")
   (git/git)
   (tomcat/tomcat)
   (user/user "testuser" :create-home true :shell :bash)
   (service/with-restart "tomcat6"
     (tomcat/server-configuration (tomcat/server))
     (hudson/tomcat-deploy :version "1.355")
     (generate-ssh-keys)
     (hudson/plugin :git)
     (hudson/plugin :github)
     (hudson/maven "default maven" "2.2.1")
     (hudson/job :maven2 "pallet"
                 :maven-name "default maven"
                 :goals "-Ptestuser clean test"
                 :group-id "org.cloudhoist"
                 :artifact-id "pallet"
                 :branches ["origin/*"]
                 :merge-target "master"
                 :github {:projectUrl "http://github.com/hugoduncan/pallet/"}
                 :maven-opts ""
                 :scm ["git://github.com/hugoduncan/pallet.git"])
     (hudson/job :maven2 "clj-ssh"
                 :maven-name "default maven"
                 :goals "-Ptestuser clean test"
                 :group-id "clj-ssh"
                 :artifact-id "clj-ssh"
                 :branches ["origin/master"]
                 :maven-opts ""
                 :scm ["git://github.com/hugoduncan/clj-ssh.git"]))))


(defn remove-ci
  [request]
  (->
   request
   (hudson/tomcat-undeploy)
   (package/package "maven2" :action :remove :purge true)
   (user/user "testuser" :action :remove)
   (tomcat/tomcat :action :remove :purge true)))

(core/defnode ci
  {}
  :bootstrap (resource/phase
              (automated-admin-user/automated-admin-user))
  :configure (resource/phase
              (ci-config))
  :restart-tomcat (resource/phase
                   (service/service "tomcat6" :action :restart))
  :reload-configuration (resource/phase
                         (hudson/reload-configuration))
  :build-clj-ssh (resource/phase (hudson/build "clj-ssh"))
  :build-pallet (resource/phase (hudson/build "pallet")))
