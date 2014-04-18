(ns pallet.crate.admin-user-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [with-context]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory exec-checked-script user]]
   [pallet.build-actions :as build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.compute :refer [node-spec]]
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
           (admin-user/configure session {})))))

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
           (admin-user/configure session {})))))

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
           (admin-user/settings session {})
           (admin-user session {:username "fred" :public-keys [(.getBytes "abc")]})
           (admin-user/configure session {})))))

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
             (admin-user/settings session {})
             (admin-user session)
             (admin-user/configure session {}))))))
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
              ;; <<<<<<< HEAD
              ;;               {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
              ;;              (with-context {:plan ["authorize-user-key"]}
              ;;                (ssh-key/authorize-key
              ;;                 session
              ;;                 user-name
              ;;                 (slurp (default-public-key-path))))
              ;;              (sudoers/configure session {}))
              ;;            (build-plan [session session]
              ;;              (sudoers/settings session {})
              ;;              (admin-user/settings session {})
              ;;              (admin-user session)
              ;;              (admin-user/configure session {})))))))
              ;; =======
              {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
             (with-context
               {:kw :authorize-user-key :msg "authorize-user-key"}
               (ssh-key/authorize-key "fred" "abc"))))
          (first
           (build-actions/build-actions [session {}]
             (admin-user/settings session {})
             (admin-user session {:username "fred" :public-keys [(.getBytes "abc")]})
             (admin-user/settings session {}))))))

  ;; (testing "with default username"
  ;;   (let [user-name (. System getProperty "user.name")]
  ;;     (is (= (first

  ;;             (build-actions/build-actions
  ;;                 [session {:phase-context "automated-admin-user"}]
  ;;               (sudoers/install session)
  ;;               (user session user-name :create-home true :shell :bash)
  ;;               (sudoers/sudoers
  ;;                session
  ;;                {}
  ;;                {:default {:env_keep "SSH_AUTH_SOCK"}}
  ;;                {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
  ;;                {})
  ;;               (with-context {:kw :authorize-user-key :msg "authorize-user-key"}
  ;;                 (ssh-key/authorize-key
  ;;                  user-name
  ;;                  (slurp (default-public-key-path))))))
  ;;            (first
  ;;             (build-actions/build-actions
  ;;                 [session {:user (make-user user-name)}]
  ;;               (automated-admin-user session)))))))
  ;; (testing "with session username"
  ;;   (let [user-name "fredxxx"]
  ;;     (is (= (first
  ;;             (build-actions/build-actions
  ;;                 [session {:phase-context "automated-admin-user"}]
  ;;               (sudoers/install session)
  ;;               (user session user-name :create-home true :shell :bash)
  ;;               (sudoers/sudoers
  ;;                session
  ;;                {}
  ;;                {:default {:env_keep "SSH_AUTH_SOCK"}}
  ;;                {user-name {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
  ;;               (with-context
  ;;                 {:kw :authorize-user-key :msg "authorize-user-key"}
  ;;                 (ssh-key/authorize-key
  ;;                  session
  ;;                  user-name
  ;;                  (slurp (default-public-key-path))))))
  ;;            (first
  ;;             (build-actions/build-actions
  ;;                 [session {:environment {:user (make-user user-name)}}]
  ;;               (automated-admin-user session)))))))
  )

;; (deftest create-admin-test
;;   (testing "with defaults"
;;     (with-redefs [pallet.actions/plan-flag-kw (constantly :flagxxx)]
;;       (is (script-no-comment=
;;            (first
;;             (build-actions/build-actions
;;                 [session {:phase-context "create-admin"}]
;;               (sudoers/install session)
;;               (with-action-options session {:error-on-non-zero-exit false}
;;                 (fragment ("getent" passwd fred)))
;;               (user "fred" :create-home true :shell :bash)
;;               (directory session (fragment (user-home fred)) :owner "fred")
;;               (sudoers/sudoers
;;                session
;;                {}
;;                {:default {:env_keep "SSH_AUTH_SOCK"}}
;;                {"fred" {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
;;               (with-context
;;                 {:kw :authorize-user-key :msg "authorize-user-key"}
;;                 (ssh-key/authorize-key
;;                  session
;;                  "fred" (slurp (default-public-key-path))))))
;;            (first
;;             (build-actions/build-actions
;;                 [session {}]
;;               (create-admin session :username "fred"))))))))

;; (deftest live-test
;;   ;; tests a node specific admin user
;;   (live-test/test-for
;;    [image (live-test/images)]
;;    (logging/debugf "automated-admin-user live test: image %s" (pr-str image))
;;    (live-test/test-nodes
;;     [compute node-map node-types]
;;     {:aau
;;      (server-spec
;;       {:phases {:bootstrap (plan-fn [session]
;;                              (admin-user/settings session {})
;;                              (admin-user session)
;;                              (admin-user/configure session {}))
;;                 :verify (plan-fn [session]
;;                           (with-context {:plan ["Check Automated admin user"]}
;;                             (exec-checked-script
;;                              session
;;                              "is functional"
;;                              (pipe (println @SUDO_USER) ("grep" "fred")))))}}
;;       :count 1
;;       :node-spec (node-spec {:image image})
;;       :environment {:user {:username "fred"}})}
;;     (is
;;      (lift
;;       (val (first node-types)) :phase [:verify] :compute compute)))))

;; (def create-admin-test-spec
;;   (server-spec
;;    :phases {:bootstrap (plan-fn
;;                         (admin-user/create-admin)
;;                         (admin-user/create-admin
;;                          :username "xxx"
;;                          :sudo false)
;;                         (admin-user/create-admin
;;                          :username "yyy"
;;                          :sudo false
;;                          :user-options {:shell :sh}))
;;             :verify (plan-fn
;;                      (context/with-phase-context
;;                        {:kw :automated-admin-user
;;                         :msg "Check Automated admin user"}
;;                        (exec-checked-script
;;                         "is functional"
;;                         (pipe (println @SUDO_USER)
;;                               ("grep" ~(:username (admin-user))))
;;                         (not ("grep" "xxx" "/etc/sudoers"))
;;                         (user-home "xxx")
;;                         (pipe
;;                          ("getent" "yyy")
;;                          (not ("grep" "bash"))))))}))
