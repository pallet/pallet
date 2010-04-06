(ns pallet.crate.mysql
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.target :only [packager]]
   [pallet.resource :only [defcomponent]]
   [pallet.stevedore :only [script]]
   [pallet.template :only [deftemplate apply-templates]]))

(def mysql-package-names
     {:yum [ "mysql-devel"]
      :aptitude [ "libmysqlclient15-dev" ]})

(def mysql-my-cnf
     {:yum "/etc/my.cnf"
      :aptitude "/etc/mysql/my.cnf"})

(defn mysql-client []
  (doseq [pkg (mysql-package-names (packager))]
    (package pkg)))

(defn mysql-server
  ([root-password] (mysql-server root-password true))
  ([root-password start-on-boot]
     (package-manager
      :debconf
      (str "mysql-server-5.1 mysql-server/root_password " root-password)
      (str "mysql-server-5.1 mysql-server/root_password_again " root-password)
      (str "mysql-server-5.1 mysql-server/start_on_boot boolean " start-on-boot))
     (package "mysql-server")))

(deftemplate my-cnf-template
  [string]
  {{:path (mysql-my-cnf (packager)) :owner "root" :mode "0440"}
   string})

(defn mysql-conf* [config]
  (apply-templates my-cnf-template [config]))

(defcomponent mysql-conf
  "my.cnf configuration file for mysql"
  mysql-conf* [config])

(defn mysql-script*
  [username password sql-script]
  (script (mysql "-u" ~username ~(str "--password=" password)
                 ~(str "<<EOF\n" sql-script "\nEOF"))))

(defcomponent mysql-script
  "Execute a mysql script"
  mysql-script* [username password sql-script])

;yum install php-mysql mysql mysql-server
;/sbin/chkconfig --levels 235 mysqld on
;/etc/init.d/mysqld start
