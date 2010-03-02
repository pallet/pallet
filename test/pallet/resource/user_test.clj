(ns pallet.resource.user-test
  (:use [pallet.resource.user] :reload-all)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils))

(deftest create-user-test
  (is (= "useradd --create-home user1"
         (script (create-user "user1"  ~{:create-home true})))))

(deftest modify-user-test
  (is (= "usermod --home=/home2/user1 user1"
         (script (modify-user "user1"  ~{:home "/home2/user1"})))))

(deftest apply-user-test
  (is (= "if grep \"^user1:\" /etc/passwd; then usermod  user1;else useradd  user1;fi\n"
         (apply-user "user1" :action :create))))
