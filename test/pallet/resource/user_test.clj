(ns pallet.resource.user-test
  (:use pallet.resource.user)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource :as resource]))

(use-fixtures :once with-ubuntu-script-template)

(deftest create-user-test
  (is (= "/usr/sbin/useradd --create-home user1"
         (script (create-user "user1"  ~{:create-home true})))))

(deftest modify-user-test
  (is (= "/usr/sbin/usermod --home /home2/user1 --shell /bin/bash user1"
         (script
          (modify-user "user1"  ~{:home "/home2/user1" :shell "/bin/bash"})))))

(deftest user*-create-test
  (is (= "if ! getent passwd user1; then /usr/sbin/useradd --shell /bin/bash user1;fi"
         (user* {} "user1" :action :create :shell :bash))))

(deftest user*-modify-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod  user1;else /usr/sbin/useradd  user1;fi"
         (user* {} "user1" :action :manage))))

(deftest user*-lock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --lock user1;fi"
         (user* {} "user1" :action :lock))))

(deftest user*-unlock-test
  (is (= "if getent passwd user1; then /usr/sbin/usermod --unlock user1;fi"
         (user* {} "user1" :action :unlock))))

(deftest user*-remove-test
  (is (= "if getent passwd user1; then /usr/sbin/userdel  user1;fi"
         (user* {} "user1" :action :remove))))

(deftest group-create-test
  (is (= "if ! getent group group11; then /usr/sbin/groupadd  group11;fi\n"
         (first (build-resources
                 []
                 (group "group11" :action :create))))))
