(ns pallet.resource.user-test
  (:use [pallet.resource.user] :reload-all)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils))

(deftest create-user-test
  (is (= "useradd --create-home user1"
         (script (create-user "user1"  ~{:create-home true})))))

(deftest modify-user-test
  (is (= "usermod --home /home2/user1 --shell /bin/bash user1"
         (script (modify-user "user1"  ~{:home "/home2/user1" :shell "/bin/bash"})))))

(deftest apply-user-create-test
  (is (= "if ! grep \"^user1:\" /etc/passwd; then useradd --shell /bin/bash user1;fi\n"
         (apply-user "user1" :action :create :shell :bash))))

(deftest apply-user-modify-test
  (is (= "if grep \"^user1:\" /etc/passwd; then usermod  user1;else useradd  user1;fi\n"
         (apply-user "user1" :action :manage))))

(deftest apply-user-lock-test
  (is (= "if grep \"^user1:\" /etc/passwd; then usermod --lock user1;fi\n"
         (apply-user "user1" :action :lock))))

(deftest apply-user-unlock-test
  (is (= "if grep \"^user1:\" /etc/passwd; then usermod --unlock user1;fi\n"
         (apply-user "user1" :action :unlock))))

(deftest apply-user-remove-test
  (is (= "if grep \"^user1:\" /etc/passwd; then userdel  user1;fi\n"
         (apply-user "user1" :action :remove))))
