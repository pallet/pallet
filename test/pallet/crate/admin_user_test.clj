(ns pallet.crate.admin-user-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [with-context]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory exec-script* exec-checked-script user]]
   [pallet.build-actions :as build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.compute :refer [node-spec]]
   [pallet.core.executor.plan :refer [with-plan-result-fns]]
   [pallet.crate.admin-user :as admin-user :refer [admin-user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.group :refer [lift]]
   [pallet.live-test :as live-test]
   [pallet.plan :refer [plan-fn]]
   [pallet.script.lib :refer [user-home]]
   [pallet.spec :refer [server-spec]]
   [pallet.stevedore :refer [fragment]]
   [pallet.user
    :refer [default-private-key-path default-public-key-path make-user]]
   [taoensso.timbre :as logging]))

(use-fixtures :once (logging-threshold-fixture))

(deftest automated-admin-user-test
  (testing "defaults, user doesn't exist"
    (with-plan-result-fns {:pallet.crate.admin-user/getent
                           (fn [action] (assoc action :exit 2))}
      (is (=
           (build-plan [session {}]
             (sudoers/settings session {})
             (sudoers/install session {})
             (with-action-options session
               {:action-id :pallet.crate.admin-user/getent
                :error-on-non-zero-exit false}
               (exec-script* session (fragment ("getent" passwd "fred"))))
             (user session "fred" {:create-home true :shell :bash})
             (directory
              session (fragment (user-home fred)) {:owner "fred"})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
              {})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key
                session
                "fred" (slurp (default-public-key-path))))
             (sudoers/configure session {}))
           (build-plan [session {}]
             (sudoers/settings session {})
             (admin-user/settings session {})
             (admin-user session {:username "fred"})
             (admin-user/configure session {}))))))
  (testing "defaults, user exists"
    (with-plan-result-fns {:pallet.crate.admin-user/getent
                           (fn [action] (assoc action :exit 0))}
      (is (=
           (build-plan [session {}]
             (sudoers/settings session {})
             (sudoers/install session {})
             (with-action-options session
               {:action-id :pallet.crate.admin-user/getent
                :error-on-non-zero-exit false}
               (exec-script* session (fragment ("getent" passwd "fred"))))
             (directory
              session (fragment (user-home fred)) {:owner "fred"})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
              {})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key
                session
                "fred" (slurp (default-public-key-path))))
             (sudoers/configure session {}))
           (build-plan [session {}]
             (sudoers/settings session {})
             (admin-user/settings session {})
             (admin-user session {:username "fred"})
             (admin-user/configure session {}))))))

  (testing "with path"
    (with-plan-result-fns {:pallet.crate.admin-user/getent
                           (fn [action] (assoc action :exit 2))}
      (is (=
           (build-plan [session {}]
             (sudoers/settings session {})
             (sudoers/install session {})
             (with-action-options session
               {:action-id :pallet.crate.admin-user/getent
                :error-on-non-zero-exit false}
               (exec-script* session (fragment ("getent" passwd "fred"))))
             (user session  "fred" {:create-home true :shell :bash})
             (directory
              session (fragment (user-home fred)) {:owner "fred"})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
              {})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key
                session
                "fred" (slurp (default-public-key-path))))
             (sudoers/configure session {}))
           (build-plan [session {}]
             (sudoers/settings session {})
             (admin-user/settings session {})
             (admin-user session {:username "fred"
                                  :public-key-paths [(default-public-key-path)]})
             (admin-user/configure session {}))))))

  (testing "with byte array"
    (with-plan-result-fns {:pallet.crate.admin-user/getent
                           (fn [action] (assoc action :exit 2))}
      (is (=
           (build-plan [session {}]
             (sudoers/settings session {})
             (sudoers/install session {})
             (with-action-options session
               {:action-id :pallet.crate.admin-user/getent
                :error-on-non-zero-exit false}
               (exec-script* session (fragment ("getent" passwd "fred"))))
             (user session "fred" {:create-home true :shell :bash})
             (directory
              session (fragment (user-home fred)) {:owner "fred"})
             (sudoers/sudoers
              session
              {}
              {:default {:env_keep "SSH_AUTH_SOCK"}}
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
              {})
             (with-context {:plan ["authorize-user-key"]}
               (ssh-key/authorize-key session "fred" "abc"))
             (sudoers/configure session {}))
           (build-plan [session {}]
             (sudoers/settings session {})
             (admin-user/settings session {})
             (admin-user session {:username "fred" :public-keys [(.getBytes "abc")]})
             (admin-user/configure session {}))))))

  (testing "with default username"
    (let [user-name (. System getProperty "user.name")]
      (with-plan-result-fns {:pallet.crate.admin-user/getent
                             (fn [action] (assoc action :exit 2))}
        (is (=
             (build-plan [session {}]
               (sudoers/settings session {})
               (sudoers/install session {})
               (with-action-options session
                 {:action-id :pallet.crate.admin-user/getent
                  :error-on-non-zero-exit false}
                 (exec-script* session (fragment ("getent" passwd ~user-name))))
               (user session user-name {:create-home true :shell :bash})
               (directory
                session (fragment (user-home ~user-name)) {:owner user-name})
               (sudoers/sudoers
                session
                {}
                {:default {:env_keep "SSH_AUTH_SOCK"}}
                {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
                {})
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
               (admin-user/settings session {})
               (admin-user session)
               (admin-user/configure session {})))))))
  (testing "with session username"
    (let [user-name "fredxxx"
          session {:execution-state
                   {:user (make-user
                           user-name
                           {:private-key-path (default-private-key-path)
                            :public-key-path (default-public-key-path)})}}]
      (with-plan-result-fns {:pallet.crate.admin-user/getent
                             (fn [action] (assoc action :exit 2))}
        (is (=
             (build-plan [session session]
               (sudoers/settings session {})
               (sudoers/install session {})
               (with-action-options session
                 {:action-id :pallet.crate.admin-user/getent
                  :error-on-non-zero-exit false}
                 (exec-script* session (fragment ("getent" passwd ~user-name))))
               (user session user-name {:create-home true :shell :bash})
               (directory
                session (fragment (user-home ~user-name)) {:owner user-name})
               (sudoers/sudoers
                session
                {}
                {:default {:env_keep "SSH_AUTH_SOCK"}}
                {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
                {})
               (with-context
                 {:kw :authorize-user-key :msg "authorize-user-key"}
                 (ssh-key/authorize-key session "fred" "abc"))))
            (first
             (build-actions/build-actions [session {}]
               (admin-user/settings session {})
               (admin-user
                session {:username "fred" :public-keys [(.getBytes "abc")]})
               (admin-user/settings session {}))))))))
