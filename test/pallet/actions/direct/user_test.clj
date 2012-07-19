(ns pallet.actions.direct.user-test
  (:use
   [pallet.actions :only [user group]]
   [pallet.actions.direct.user :only [user*]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.stevedore :only [script]]
   clojure.test)
  (:require
   [pallet.test-utils :as test-utils]
   [pallet.script :as script]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 (logging-threshold-fixture))

(deftest user*-create-test
  (is (= (str "if ! ( getent passwd user1 );"
              " then /usr/sbin/useradd --shell \"/bin/bash\" user1;fi")
         (user* {} "user1" :action :create :shell :bash))))

(deftest user*-modify-test
  (is (= (str "if getent passwd user1;"
              " then :;else /usr/sbin/useradd user1;fi")
         (user* {} "user1" :action :manage)))
  (is (= (str
          "if getent passwd user1;"
          " then /usr/sbin/usermod -p \"p\" user1;"
          "else /usr/sbin/useradd -p \"p\" user1;fi")
         (pallet.script/with-script-context [:fedora]
           (user* {} "user1" :action :manage :password "p")))))

(deftest user*-lock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --lock user1;fi"
         (user* {} "user1" :action :lock))))

(deftest user*-unlock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --unlock user1;fi"
         (user* {} "user1" :action :unlock))))

(deftest user*-remove-test
  (is (= "if getent passwd user1; then /usr/sbin/userdel user1;fi"
         (user* {} "user1" :action :remove))))

(deftest group-create-test
  (is (= "if ! ( getent group group11 ); then /usr/sbin/groupadd group11;fi\n"
         (first (build-actions {}
                  (group "group11" :action :create)))))
  (testing "system on rh"
    (is (="if ! ( getent group group11 ); then /usr/sbin/groupadd -r group11;fi\n"
         (first (build-actions
                    {:server {:image {:os-family :centos}}}
                  (group "group11" :action :create :system true)))))))
