(ns pallet.crate.environment-test
  (:require
   [clojure.test :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [exec-script* file]]
   [pallet.actions.impl :refer [checked-commands*]]
   [pallet.build-actions
    :refer [build-actions build-plan build-script target-session]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute.node-list :refer [make-localhost-node]]
   [pallet.crate.environment :refer [system-environment
                                     system-environment-file]]
   [pallet.group :refer [lift group-spec phase-errors]]
   [pallet.plan :refer [plan-context plan-fn]]
   [pallet.script :refer [with-script-context]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore :refer [with-script-language]]
   [pallet.test-utils
    :refer [make-node test-username no-location-info no-source-line-comments]]
   [pallet.user :refer [*admin-user*]]
   [pallet.utils :refer [tmpfile with-temporary]]))

(use-fixtures :once
  (logging-threshold-fixture)
  no-location-info
  no-source-line-comments)

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

(deftest system-environment-file-test
  (with-script-language :pallet.stevedore.bash/bash
    (is (= ["/etc/environment" true]
           (with-script-context [:ubuntu]
             (system-environment-file
              (target-session
               {:target {:override {:os-family :ubuntu}}})
              "xx" {}))))
    (is (= ["/etc/profile.d/xx.sh" false]
           (with-script-context [:centos]
             (system-environment-file
              (target-session
               {:target {:override {:os-family :centos}}})
              "xx" {}))))))


(deftest service-test
  (is
   (=
    (build-plan [session {}]
      (plan-context "system-environment" {}
        (with-action-options session {:new-login-after-action true}
          (exec-script*
           session
           (checked-commands*
            "Add testenv environment to /etc/environment"
            [(stevedore/chained-script
              (if-not (file-exists? "/etc/environment")
                (lib/heredoc "/etc/environment"
                             "# environment file created by pallet\n" {})))
             (stevedore/chained-script
              (defn pallet_set_env [k v s]
                (if-not ("grep" (quoted @s) "/etc/environment" "2>&-")
                  (chain-or
                   (chain-and
                    ("sed" -i (lib/sed-ext)
                     -e (quoted "/$${k}=/ d") "/etc/environment")
                    ("sed" -i (lib/sed-ext)
                     -e (quoted "$ a \\\\\n${s}") "/etc/environment"))
                   ("exit" 1))))
              (var vv (quoted "1"))
              ("pallet_set_env"
               (quoted "A") (quoted @vv)
               (quoted (str  "A=\\\"" @vv "\\\"")))
              (var vv (quoted "b"))
              ("pallet_set_env"
               (quoted "B") (quoted @vv)
               (quoted (str  "B=\\\"" @vv "\\\""))))])))))
    (build-plan [session {}]
      (system-environment session "testenv" {"A" 1 :B "b"}))))
  (is
   (=
    (build-plan [session {}]
      (plan-context "system-environment" {}
        (with-action-options session {:new-login-after-action true}
          (exec-script*
           session
           (checked-commands*
            "Add testenv environment to /etc/environment"
            [(stevedore/chained-script
              (if-not (file-exists? "/etc/environment")
                (lib/heredoc "/etc/environment"
                             "# environment file created by pallet\n" {})))
             (stevedore/chained-script
              (defn pallet_set_env [k v s]
                (if-not ("grep" (quoted @s) "/etc/environment" "2>&-")
                  (chain-or
                   (chain-and
                    ("sed" -i (lib/sed-ext)
                     -e (quoted "/$${k}=/ d") "/etc/environment")
                    ("sed" -i (lib/sed-ext)
                     -e (quoted "$ a \\\\\n${s}") "/etc/environment"))
                   ("exit" 1))))
              (var vv "'1'")
              ("pallet_set_env"
               (quoted "A") (quoted @vv)
               (quoted (str  "A=\\\"" @vv "\\\"")))
              (var vv "'b'")
              ("pallet_set_env"
               (quoted "B") (quoted @vv)
               (quoted (str  "B=\\\"" @vv "\\\""))))])))))
    (build-plan [session {}]
      (system-environment session "testenv" {"A" 1 :B "b"} :literal true)))))

;;; TODO
;; (deftest service-local-test
;;   (with-temporary [env-file (tmpfile)]
;;     (.delete env-file)
;;     (let [user (local-test-user)
;;           node (make-localhost-node {})
;;           a (atom nil)
;;           path (.getAbsolutePath env-file)
;;           get-sysenv (fn []
;;                        (reset! a (system-environment-file
;;                                   "pallet-testenv" {:path path})))
;;           local (group-spec "local")]
;;       (testing "insert"
;;         (let [result (lift {local node}
;;                            :user user
;;                            :phase (plan-fn [session]
;;                                     (with-action-options
;;                                       session
;;                                       {:script-trace true}
;;                                       (system-environment
;;                                        session
;;                                        "pallet-testenv"
;;                                        {"a" "$xxxx"}
;;                                        :literal true
;;                                        :path path))
;;                                     (get-sysenv)))
;;               [path shared] @a]
;;           (is (nil? (phase-errors result)))
;;           (is @a)
;;           (is path)
;;           (is shared)
;;           (is (= (.getAbsolutePath env-file) path))
;;           (is (slurp path))
;;           (if shared
;;             (do
;;               (is (re-find #"a=\"\$xxxx\"" (slurp path))))
;;             (= "a=\"$xxxx\"" (slurp path)))
;;           (.startsWith (slurp path) "# ")))
;;       (testing "replace"
;;         (let [result (lift {local node} :user user
;;                            :phase (plan-fn [session]
;;                                     (system-environment
;;                                      session
;;                                      "pallet-testenv"
;;                                      {"a" "$xxyy"}
;;                                      :literal true
;;                                      :path path)
;;                                     (get-sysenv)))
;;               [path shared] @a]
;;           (is (nil? (phase-errors result)))
;;           (is path)
;;           (is shared)
;;           (is (= (.getAbsolutePath env-file) path))
;;           (if shared
;;             (is (re-find #"a=\"\$xxyy\"" (slurp path)))
;;             (is (= "a=\"$xxyy\"" (slurp path)))))))))
