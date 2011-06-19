(ns pallet.crate.automated-admin-user-test
  (:use pallet.crate.automated-admin-user)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.user :as user]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging])
  (:use
   clojure.test
   pallet.test-utils))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (= (first
            (build-actions/build-actions
             {}
             (sudoers/install)
             (user/user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key
              "fred" (slurp (pallet.utils/default-public-key-path)))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred"))))))

  (testing "with path"
    (is (= (first
            (build-actions/build-actions
             {}
             (sudoers/install)
             (user/user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key
              "fred" (slurp (pallet.utils/default-public-key-path)))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user
              "fred" (pallet.utils/default-public-key-path)))))))

  (testing "with byte array"
    (is (= (first
            (build-actions/build-actions
             {}
             (sudoers/install)
             (user/user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {} {} {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (ssh-key/authorize-key "fred" "abc")))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred" (.getBytes "abc")))))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (is (= (first
              (build-actions/build-actions
               {}
               (sudoers/install)
               (user/user user-name :create-home true :shell :bash)
               (sudoers/sudoers
                {} {} {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
               (ssh-key/authorize-key
                user-name (slurp (pallet.utils/default-public-key-path)))))
             (first
              (build-actions/build-actions
               {:user (utils/make-user user-name)}
               (automated-admin-user)))))))
    (testing "with session username"
    (let [user-name "fredxxx"]
      (is (= (first
              (build-actions/build-actions
               {}
               (sudoers/install)
               (user/user user-name :create-home true :shell :bash)
               (sudoers/sudoers
                {} {} {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
               (ssh-key/authorize-key
                user-name (slurp (pallet.utils/default-public-key-path)))))
             (first
              (build-actions/build-actions
               {:user (utils/make-user user-name)}
               (automated-admin-user))))))))

(deftest live-test
  ;; tests a node specific admin user
  (live-test/test-for
   [image (live-test/images)]
   (logging/trace
    (format "automated-admin-user live test: image %s" (pr-str image)))
   (live-test/test-nodes
    [compute node-map node-types]
    {:pgtest
     (->
      (core/server-spec
       :phases {:bootstrap (phase/phase-fn
                            (automated-admin-user/automated-admin-user))
                :verify (phase/phase-fn
                         (exec-script/exec-checked-script
                          "check automated admin user functional"
                          (pipe (echo @SUDO_USER) (grep "fred"))))}
       :count 1
       :node-spec (core/node-spec :image image)
       :environment {:user {:username "fred"}}))}
    (is
     (core/lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
