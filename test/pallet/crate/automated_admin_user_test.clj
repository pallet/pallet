(ns pallet.crate.automated-admin-user-test
  (:require
   [clojure.test :refer :all]
   [taoensso.timbre :as logging]
   [com.palletops.log-config.timbre :refer [with-context]]
   [pallet.actions :refer [exec-checked-script user]]
   [pallet.build-actions :as build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.compute :refer [node-spec]]
   [pallet.crate.automated-admin-user :as automated-admin-user
    :refer [create-admin-user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.group :refer [lift]]
   [pallet.live-test :as live-test]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :refer [server-spec]]
   [pallet.user
    :refer [default-private-key-path default-public-key-path make-user]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (=
         (build-plan [session {}]
           (sudoers/settings session {})
           (sudoers/install session {})
           (user session "fred" {:create-home true :shell :bash})
           (sudoers/sudoers
            session
            {}
            {:default {:env_keep "SSH_AUTH_SOCK"}}
            {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
           (with-context {:plan ["authorize-user-key"]}
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
    (is (=
         (build-plan [session {}]
           (sudoers/settings session {})
           (sudoers/install session {})
           (user session  "fred" {:create-home true :shell :bash})
           (sudoers/sudoers
            session
            {}
            {:default {:env_keep "SSH_AUTH_SOCK"}}
            {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
           (with-context {:plan ["authorize-user-key"]}
             (ssh-key/authorize-key
              session
              "fred" (slurp (default-public-key-path))))
           (sudoers/configure session {}))
         (build-plan [session {}]
           (sudoers/settings session {})
           (automated-admin-user/settings session {})
           (create-admin-user session "fred" (default-public-key-path))
           (automated-admin-user/configure session {})))))

  (testing "with byte array"
    (is (=
         (build-plan [session {}]
           (sudoers/settings session {})
           (sudoers/install session {})
           (user session "fred" {:create-home true :shell :bash})
           (sudoers/sudoers
            session
            {}
            {:default {:env_keep "SSH_AUTH_SOCK"}}
            {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
           (with-context {:plan ["authorize-user-key"]}
             (ssh-key/authorize-key session "fred" "abc"))
           (sudoers/configure session {}))
         (build-plan [session {}]
           (sudoers/settings session {})
           (automated-admin-user/settings session {})
           (create-admin-user session "fred" (.getBytes "abc"))
           (automated-admin-user/configure session {})))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (is (=
           (build-plan [session {}]
             (sudoers/settings session {})
             (sudoers/install session {})
             (user session user-name {:create-home true :shell :bash})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key
                session
                user-name
                (slurp (default-public-key-path))))
             (sudoers/configure session {}))
           (build-plan [session
                        {:execution-state
                         {:user (make-user
                                 user-name
                                 {:private-key-path (default-private-key-path)
                                  :public-key-path (default-public-key-path)})}}]
             (sudoers/settings session {})
             (automated-admin-user/settings session {})
             (create-admin-user session)
             (automated-admin-user/configure session {}))))))
  (testing "with session username"
    (let [user-name "fredxxx"
          session {:execution-state
                   {:user (make-user
                           user-name
                           {:private-key-path (default-private-key-path)
                            :public-key-path (default-public-key-path)})}}]
      (is (=
           (build-plan [session session]
             (sudoers/settings session {})
             (sudoers/install session {})
             (user session user-name {:create-home true :shell :bash})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key
                session
                user-name
                (slurp (default-public-key-path))))
             (sudoers/configure session {}))
           (build-plan [session session]
             (sudoers/settings session {})
             (automated-admin-user/settings session {})
             (create-admin-user session)
             (automated-admin-user/configure session {})))))))

(deftest live-test
  ;; tests a node specific admin user
  (live-test/test-for
   [image (live-test/images)]
   (logging/debugf "automated-admin-user live test: image %s" (pr-str image))
   (live-test/test-nodes
    [compute node-map node-types]
    {:aau
     (server-spec
      {:phases {:bootstrap (plan-fn [session]
                             (automated-admin-user/settings session {})
                             (create-admin-user session)
                             (automated-admin-user/configure session {}))
                :verify (plan-fn [session]
                          (with-context {:plan ["Check Automated admin user"]}
                            (exec-checked-script
                             session
                             "is functional"
                             (pipe (println @SUDO_USER) ("grep" "fred")))))}}
      :count 1
      :node-spec (node-spec {:image image})
      :environment {:user {:username "fred"}})}
    (is
     (lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
