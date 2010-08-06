(ns pallet.crate.mysql
  (:require
   [pallet.resource.package :as package]
   [pallet.resource :as resource]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.target :only [packager]]
   [pallet.resource :only [defresource]]
   [pallet.stevedore :only [script]]
   [pallet.template :only [deftemplate apply-templates]]))

(def mysql-my-cnf
     {:yum "/etc/my.cnf"
      :aptitude "/etc/mysql/my.cnf"})

(defn mysql-client []
  (package/packages :yum [ "mysql-devel"]
                    :aptitude [ "libmysqlclient15-dev" ]))

(defn mysql-server
  ([root-password] (mysql-server root-password true))
  ([root-password start-on-boot]
     (package/package-manager
      :debconf
      (str "mysql-server-5.1 mysql-server/root_password password " root-password)
      (str "mysql-server-5.1 mysql-server/root_password_again password " root-password)
      (str "mysql-server-5.1 mysql-server/start_on_boot boolean " start-on-boot)
      (str "mysql-server-5.1 mysql-server-5.1/root_password password " root-password)
      (str "mysql-server-5.1 mysql-server-5.1/root_password_again password " root-password)
      (str "mysql-server-5.1 mysql-server/start_on_boot boolean " start-on-boot))
     (package/package "mysql-server")
     (resource/parameters
      [:mysql :root-password] root-password)))

(deftemplate my-cnf-template
  [string]
  {{:path (mysql-my-cnf (packager)) :owner "root" :mode "0440"}
   string})

(defn mysql-conf* [config]
  (apply-templates my-cnf-template [config]))

(defresource mysql-conf
  "my.cnf configuration file for mysql"
  mysql-conf* [config])

(defn mysql-script*
  [username password sql-script]
  (stevedore/checked-script
   "MYSQL command"
   ("{\n" mysql "-u" ~username ~(str "--password=" password)
    ~(str "<<EOF\n" (.replace sql-script "`" "\\`") "\nEOF\n}"))))

(defresource mysql-script
  "Execute a mysql script"
  mysql-script* [username password sql-script])

(defn create-database
  ([name] (create-database name "root" (parameter/lookup :mysql :root-password)))
  ([name username root-password]
     (mysql-script
      username root-password (format "CREATE DATABASE IF NOT EXISTS `%s`" name))))

(defn create-user
  ([user password]
     (create-user user password "root" (parameter/lookup :mysql :root-password)))
  ([user password username root-password]
     (mysql-script
      username root-password
      (format "GRANT USAGE ON *.* TO %s IDENTIFIED BY '%s'" user password))))

(defn grant
  ([privileges level user]
     (grant privileges level user "root" (parameter/lookup :mysql :root-password)))
  ([privileges level user username root-password]
     (mysql-script
      username root-password (format "GRANT %s ON %s TO %s" privileges level user))))
