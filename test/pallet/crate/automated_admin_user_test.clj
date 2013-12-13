(ns pallet.crate.automated-admin-user-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-checked-script user]]
   [pallet.api :refer [lift make-user node-spec plan-fn server-spec]]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.context :as context]
   [pallet.context :as logging]
   [pallet.core.user :refer [default-public-key-path]]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.automated-admin-user :refer [automated-admin-user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.live-test :as live-test]))

(use-fixtures :once (logging-threshold-fixture))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (context/with-phase-context
               {:kw :authorize-user-key :msg "authorize-user-key"}
               (ssh-key/authorize-key
                "fred" (slurp (default-public-key-path))))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred"))))))

  (testing "with path"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (context/with-phase-context
               {:kw :authorize-user-key :msg "authorize-user-key"}
               (ssh-key/authorize-key
                "fred" (slurp (default-public-key-path))))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user
              "fred" (default-public-key-path)))))))

  (testing "with byte array"
    (is (= (first
            (build-actions/build-actions
             {:phase-context "automated-admin-user"}
             (sudoers/install)
             (user "fred" :create-home true :shell :bash)
             (sudoers/sudoers
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (context/with-phase-context
               {:kw :authorize-user-key :msg "authorize-user-key"}
               (ssh-key/authorize-key "fred" "abc"))))
           (first
            (build-actions/build-actions
             {}
             (automated-admin-user "fred" (.getBytes "abc")))))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (is (= (first

              (build-actions/build-actions
               {:phase-context "automated-admin-user"}
               (sudoers/install)
               (user user-name :create-home true :shell :bash)
               (sudoers/sudoers
                {}
                {:default {:env_keep "SSH_AUTH_SOCK"}}
                {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
               (context/with-phase-context
                 {:kw :authorize-user-key :msg "authorize-user-key"}
                 (ssh-key/authorize-key
                  user-name
                  (slurp (default-public-key-path))))))
             (first
              (build-actions/build-actions
               {:user (make-user user-name)}
               (automated-admin-user)))))))
  (testing "with session username"
    (let [user-name "fredxxx"]
      (is (= (first
              (build-actions/build-actions
                  {:phase-context "automated-admin-user"
                   :environment {:user (make-user user-name)}}
                (sudoers/install)
                (user user-name :create-home true :shell :bash)
                (sudoers/sudoers
                 {}
                 {:default {:env_keep "SSH_AUTH_SOCK"}}
                 {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
                (context/with-phase-context
                  {:kw :authorize-user-key :msg "authorize-user-key"}
                  (ssh-key/authorize-key
                   user-name
                   (slurp (default-public-key-path))))))
             (first
              (build-actions/build-actions
                  {:environment {:user (make-user user-name)}}
               (automated-admin-user))))))))

(deftest live-test
  ;; tests a node specific admin user
  (live-test/test-for
   [image (live-test/images)]
   (logging/debugf "automated-admin-user live test: image %s" (pr-str image))
   (live-test/test-nodes
    [compute node-map node-types]
    {:aau
     (server-spec
      :phases {:bootstrap (plan-fn
                           (automated-admin-user/automated-admin-user))
               :verify (plan-fn
                        (context/with-phase-context
                          {:kw :automated-admin-user
                           :msg "Check Automated admin user"}
                          (exec-checked-script
                           "is functional"
                           (pipe (println @SUDO_USER) ("grep" "fred")))))}
      :count 1
      :node-spec (node-spec :image image)
      :environment {:user {:username "fred"}})}
    (is
     (lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
