(ns pallet.resource.user-test
  (:use [pallet.resource.user] :reload-all)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils))

;; (deftest useradd-test
;;   (is (= "useradd user1"
;;          (script (create-user "user1")))))
