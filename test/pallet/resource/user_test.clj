(ns pallet.resource.user-test
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource.user :as user]
   [pallet.build-actions :as build-actions]
   [pallet.script :as script]))

(use-fixtures :once with-ubuntu-script-template)


(deftest user*-create-test
  (is (= (str "if ! getent passwd user1;"
              " then /usr/sbin/useradd --shell \"/bin/bash\" user1;fi")
         (user/user* {} "user1" :action :create :shell :bash))))

(deftest user*-modify-test
  (is (= (str "if getent passwd user1;"
              " then /usr/sbin/usermod user1;else /usr/sbin/useradd user1;fi")
         (user/user* {} "user1" :action :manage))))

(deftest user*-lock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --lock user1;fi"
         (user/user* {} "user1" :action :lock))))

(deftest user*-unlock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --unlock user1;fi"
         (user/user* {} "user1" :action :unlock))))

(deftest user*-remove-test
  (is (= "if getent passwd user1; then /usr/sbin/userdel user1;fi"
         (user/user* {} "user1" :action :remove))))

(deftest group-create-test
  (is (= "if ! getent group group11; then /usr/sbin/groupadd group11;fi\n"
         (first (build-actions/build-actions
                 {}
                 (user/group "group11" :action :create)))))
  (testing "system on rh"
    (is (= "if ! getent group group11; then /usr/sbin/groupadd -r group11;fi\n"
           (first (build-actions/build-actions
                   {:server {:image {:os-family :centos}}}
                   (user/group "group11" :action :create :system true)))))))
