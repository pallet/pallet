(ns pallet.core.api-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.api :refer :all]
   [pallet.core.executor.plan :as plan]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.session :as session :refer [executor recorder]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest execute-action-test
  (testing "execute-action"
    (let [session (session/create {:executor (plan/executor)
                                   :recorder (in-memory-recorder)})
          result (execute-action session {:a 1})]
      (is (= {:target nil :user nil :result {:a 1}} result)
          "returns the result of the action")
      (is (= [result] (plan/plan (executor session)))
          "uses the session executor")
      (is (= [result] (results (recorder session)))
          "records the results in the session recorder"))))

(deftest execute-localhost-test
  (let [session (session/create {:executor (ssh/ssh-executor)})
        result (execute session
                        (localhost)
                        (fn [session]
                          (exec-script* session "ls")
                          :rv))]
    (is (map? result))
    (is (= 1 (count (:action-results result))))
    (is (= :rv (:return-value result)))
    (is (= (localhost) (:node result)))))
