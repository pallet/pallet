(ns pallet.actions.direct.exec-script-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions.direct.exec-script :refer [exec* exec-script**]]
   [pallet.script.lib :refer [ls]]
   [pallet.user :refer [*admin-user*]]))

(deftest exec-script*-test
  (is (= "ls file"
         (exec-script** nil "ls file"))))

(deftest exec-test
  (is (= [{:language :python} "print 'x'"]
         (exec* nil {:language :python} "print 'x'"))))
