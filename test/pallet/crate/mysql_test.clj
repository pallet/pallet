(ns pallet.crate.mysql-test
  (:use [pallet.crate.mysql] :reload-all)
  (:use [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.target :as target]
   [pallet.resource.package :as package]))

(use-fixtures :each with-null-target)


(deftest mysql-server-test
  (parameter/with-parameters [:default]
    (is (=
         (stevedore/do-script
          (package/package-manager*
           :debconf
           (str "mysql-server-5.1 mysql-server/root_password password " "pwd")
           (str "mysql-server-5.1 mysql-server/root_password_again password " "pwd")
           (str "mysql-server-5.1 mysql-server/start_on_boot boolean " true)
           (str "mysql-server-5.1 mysql-server-5.1/root_password password " "pwd")
           (str "mysql-server-5.1 mysql-server-5.1/root_password_again password " "pwd")
           (str "mysql-server-5.1 mysql-server/start_on_boot boolean " true))
          "{ debconf-set-selections <<EOF\ndebconf debconf/frontend select noninteractive\ndebconf debconf/frontend seen false\nEOF\n}\n"
          (package/package* "mysql-server")
          (resource/parameters*
           [:mysql :root-password] "pwd"))
         (target/with-target nil {:tag :n :image [:ubuntu]}
           (build-resources [] (mysql-server "pwd")))))))

(deftest mysql-conf-test
  (is (= "file=/etc/mysql/my.cnf\ncat > ${file} <<EOF\n[client]\nport = 3306\n\nEOF\nchmod 0440 ${file}\nchown root ${file}\n"
         (build-resources [] (mysql-conf "[client]\nport = 3306\n")))))

(deftest create-database-test
  (is (= (stevedore/do-script
          (mysql-script* "root" "pwd" "CREATE DATABASE IF NOT EXISTS `db`"))
         (build-resources [] (create-database "db" "root" "pwd")))))

(deftest create-user-test
  (is (= (stevedore/do-script
          (mysql-script* "root" "pwd" "GRANT USAGE ON *.* TO user IDENTIFIED BY 'pw'"))
         (build-resources [] (create-user "user" "pw" "root" "pwd")))))

(deftest grant-test
  (is (= (stevedore/do-script
          (mysql-script* "root" "pwd" "GRANT ALL PRIVILEGES ON `db`.* TO user"))
         (build-resources [] (grant "ALL PRIVILEGES" "`db`.*" "user" "root" "pwd")))))
