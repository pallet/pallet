(ns pallet.crate.automated-admin-user-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-checked-script user]]
   [pallet.build-actions :as build-actions :refer [build-plan]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :refer [node-spec]]
   [pallet.context :as context]
   [pallet.context :as logging]
   [pallet.crate.automated-admin-user :as automated-admin-user
    :refer [create-admin-user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.group :refer [lift]]
   [pallet.live-test :as live-test]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :refer [server-spec]]
   [pallet.user :refer [default-public-key-path make-user]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (=
         (build-plan [session {}]
           (sudoers/settings session {})
           (sudoers/install session {})
           (user session "fred" :create-home true :shell :bash)
           (sudoers/sudoers
            session
            {}
            {:default {:env_keep "SSH_AUTH_SOCK"}}
            {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
           (context/with-phase-context
             {:kw :authorize-user-key :msg "authorize-user-key"}
             (ssh-key/authorize-key
              session
              "fred" (slurp (default-public-key-path))))
           (sudoers/configure session {}))
         (build-plan [session {}]
           (sudoers/settings session {})
           (automated-admin-user/settings session {})
           (create-admin-user session "fred")
           (automated-admin-user/configure session {})))))

  (testing "with path"
    (is (= (first
            (build-plan [session {}]
              (sudoers/settings {})
              (sudoers/install {})
              (user "fred" :create-home true :shell :bash)
              (sudoers/sudoers
               {}
               {:default {:env_keep "SSH_AUTH_SOCK"}}
               {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
              (context/with-phase-context
                {:kw :authorize-user-key :msg "authorize-user-key"}
                (ssh-key/authorize-key
                 "fred" (slurp (default-public-key-path))))
              (sudoers/configure {})))
           (first
            (build-plan [session {}]
              (sudoers/settings {})
              (automated-admin-user/settings {})
              (create-admin-user "fred" (default-public-key-path))
              (automated-admin-user/configure {}))))))

  (testing "with byte array"
    (is (= (first
            (build-plan [session {}]
              (sudoers/settings {})
              (sudoers/install {})
              (user "fred" :create-home true :shell :bash)
              (sudoers/sudoers
               {}
               {:default {:env_keep "SSH_AUTH_SOCK"}}
               {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
              (context/with-phase-context
                {:kw :authorize-user-key :msg "authorize-user-key"}
                (ssh-key/authorize-key "fred" "abc"))
              (sudoers/configure {})))
           (first
            (build-plan [session {}]
              (sudoers/settings {})
              (automated-admin-user/settings {})
              (create-admin-user "fred" (.getBytes "abc"))
              (automated-admin-user/configure {}))))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (is (= (first
              (build-plan [session {}]
                (sudoers/settings {})
                (sudoers/install {})
                (user user-name :create-home true :shell :bash)
                (sudoers/sudoers
                 {}
                 {:default {:env_keep "SSH_AUTH_SOCK"}}
                 {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
                (context/with-phase-context
                  {:kw :authorize-user-key :msg "authorize-user-key"}
                  (ssh-key/authorize-key
                   user-name
                   (slurp (default-public-key-path))))
                (sudoers/configure {})))
             (first
              (build-plan [session {:user (make-user user-name)}]
                (sudoers/settings session {})
                (automated-admin-user/settings session {})
                (create-admin-user session)
                (automated-admin-user/configure session {})))))))
  (testing "with session username"
    (let [user-name "fredxxx"]
      (is (= (first
              (build-plan [session {}]
                (sudoers/settings {})
                (sudoers/install {})
                (user user-name :create-home true :shell :bash)
                (sudoers/sudoers
                 {}
                 {:default {:env_keep "SSH_AUTH_SOCK"}}
                 {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
                (context/with-phase-context
                  {:kw :authorize-user-key :msg "authorize-user-key"}
                  (ssh-key/authorize-key
                   user-name
                   (slurp (default-public-key-path))))
                (sudoers/configure {})))
             (first
              (build-plan [session {:environment {:user (make-user user-name)}}]
                (sudoers/settings session {})
                (automated-admin-user/settings session {})
                (create-admin-user session)
                (automated-admin-user/configure session {}))))))))

(deftest live-test
  ;; tests a node specific admin user
  (live-test/test-for
   [image (live-test/images)]
   (logging/debugf "automated-admin-user live test: image %s" (pr-str image))
   (live-test/test-nodes
    [compute node-map node-types]
    {:aau
     (server-spec
      :phases {:bootstrap (plan-fn [session]
                            (automated-admin-user/settings session {})
                            (create-admin-user session)
                            (automated-admin-user/configure session {}))
               :verify (plan-fn [session]
                         (context/with-phase-context
                           {:kw :automated-admin-user
                            :msg "Check Automated admin user"}
                           (exec-checked-script
                            session
                            "is functional"
                            (pipe (println @SUDO_USER) ("grep" "fred")))))}
      :count 1
      :node-spec (node-spec :image image)
      :environment {:user {:username "fred"}})}
    (is
     (lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
