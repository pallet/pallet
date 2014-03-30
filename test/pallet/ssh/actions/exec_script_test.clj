(ns pallet.ssh.actions.exec-script-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.actions :refer [exec-script exec-checked-script]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.core.executor.ssh :refer [ssh-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.plan :refer [plan-errors execute-plan plan-fn]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils
    :refer [test-username
            with-bash-script-language
            with-ubuntu-script-template
            with-no-source-line-comments]]
   [pallet.user :as user :refer [*admin-user* with-admin-user]]
   [pallet.utils :as utils]))

(use-fixtures :once
  with-ubuntu-script-template
  with-bash-script-language
  with-no-source-line-comments
  (logging-threshold-fixture :error))

(defn ssh-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (ssh-executor)
                       :recorder (in-memory-recorder)})
      (set-user (assoc user/*admin-user* :username (test-username)))))

(deftest exec-script-test
  (with-admin-user (assoc *admin-user*
                     :username (test-username))
    (let [session (ssh-session)
          target {:node (localhost)}]
      (testing "simple exec"
        (let [{:keys [action-results return-value] :as result}
              (execute-plan
               session
               target
               (plan-fn [session]
                 (exec-script session "ls")))
              {:keys [exit out]} return-value]
          (is return-value)
          (is (zero? exit) "Runs correctly")
          (is (not (plan-errors result)))))
      (testing "non zero exit without error"
        (let [{:keys [return-value action-results] :as result}
              (execute-plan
               session
               target
               (plan-fn [session]
                 (with-action-options session {:error-on-non-zero-exit false}
                   (exec-script session "exit 1"))))
              {:keys [exit out]} (first action-results)]
          (is (= return-value (first action-results)))
          (is (not (:error return-value)))
          (is (pos? exit) "Runs correctly")
          (is (not (plan-errors result)))))
      (testing "zero exit with error"
        (let [{:keys [action-results return-value] :as result}
              (execute-plan
               session
               target
               (plan-fn [session]
                 (exec-checked-script session "should fail" "exit 1")))
              {:keys [exit out]} (first action-results)]
          (is (not (contains? result :return-value)))
          (is (pos? exit) "Runs correctly")
          (is (plan-errors result)))))))
