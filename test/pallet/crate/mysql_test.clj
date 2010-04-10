(ns pallet.crate.mysql-test
  (:use [pallet.crate.mysql] :reload-all)
  (:use [pallet.resource :only [build-resources]]
        clojure.test))


(deftest mysql-conf-test
  (is (= "file=/etc/mysql/my.cnf\ncat > ${file} <<EOF\n[client]\nport = 3306\n\nEOF\nchmod 0440 ${file}\nchown root ${file}\n"
         (build-resources [] (mysql-conf "[client]\nport = 3306\n")))))
