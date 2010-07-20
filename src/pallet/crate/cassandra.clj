(ns pallet.crate.cassandra
  (:require
   [pallet.arguments :as arguments]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.parameter :as parameter]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.file :as file]
   [pallet.resource.service :as service]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(def install-path "/usr/local/cassandra")
(def log-path "/var/log/cassandra")
(def config-path "/etc/cassandra")
(def data-path "/var/cassandra")
(def cassandra-home install-path)
(def cassandra-user "cassandra")
(def cassandra-group "cassandra")

(defn from-package
  []
  (package/package-source
   "cassandra"
   :aptitude {:url "ppa:cassandra-ubuntu/stable"})
  (package/package-manager :update)
  (package/package "cassandra"))

(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/cassandra/%s/apache-cassandra-%s-bin.tar.gz"
   version version))

(defn install
  "Install Cassandra"
  [& options]
  (let [options (apply hash-map options)
        version (options :version "0.6.3")
        url (url version)]
    (resource/parameters
     [:cassandra :home] (format "%s-%s" install-path version)
     [:cassandra :owner] (:user options cassandra-user)
     [:cassandra :group] (:group options cassandra-group))
    (remote-directory/remote-directory
     (parameter/lookup :cassandra :home)
     :url url
     :md5-url (str url ".md5")
     :unpack :tar
     :tar-options "xz"
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group))
    (directory/directory
     log-path
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0755")
    (directory/directory
     config-path
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0755")
    (directory/directory
     data-path
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0755")
    (remote-file/remote-file
     (format "%s/log4j.properties" config-path)
     :remote-file (arguments/delayed
                   (format "%s/conf/log4j.properties"
                           (parameter/get-for [:cassandra :home])))
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0644")
    (remote-file/remote-file
     (format "%s/storage-conf.xml" config-path)
     :remote-file (arguments/delayed
                   (format "%s/conf/storage-conf.xml"
                           (parameter/get-for [:cassandra :home])))
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0644")
    (remote-file/remote-file
     (format "%s/storage-conf.xml" config-path)
     :remote-file (arguments/delayed
                   (format "%s/cassandra.in.sh"
                           (parameter/get-for [:cassandra :home])))
     :owner (parameter/lookup :cassandra :user)
     :group (parameter/lookup :cassandra :group)
     :mode "0644")
    (file/sed
     (format "%s/log4j.properties" config-path)
     {"log4j.rootLogger=INFO, CONSOLE"
      "log4j.rootLogger=INFO, ROLLINGFILE"
      "log4j.appender.ROLLINGFILE.File=cassandra.log"
      (format "log4j.appender.ROLLINGFILE.File=%s/cassandra.log" log-path)}
     {:seperator "|"})))


