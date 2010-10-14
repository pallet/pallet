(ns pallet.crate.automated-admin-user-test
  (:use pallet.crate.automated-admin-user)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.user :as user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as susudoers]
   pallet.utils)
  (:use
   clojure.test
   pallet.test-utils))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (= (first
            (build-resources
             []
             (user/user "fred" :create-home true :shell :bash)
             (susudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key
              "fred" (slurp (pallet.utils/default-public-key-path)))))
           (first
            (build-resources
             []
             (automated-admin-user "fred"))))))

  (testing "with path"
    (is (= (first
            (build-resources
             []
             (user/user "fred" :create-home true :shell :bash)
             (susudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key
              "fred" (slurp (pallet.utils/default-public-key-path)))))
           (first
            (build-resources
             []
             (automated-admin-user
              "fred" (pallet.utils/default-public-key-path)))))))

  (testing "with byte array"
    (is (= (first
            (build-resources
             []
             (user/user "fred" :create-home true :shell :bash)
             (susudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key "fred" "abc")))
           (first
            (build-resources
             []
             (automated-admin-user "fred" (.getBytes "abc"))))))))
