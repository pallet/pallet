(ns pallet.core.api-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.api :refer :all]
   [pallet.core.executor.plan :refer [executor plan]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.session :as session :refer [recorder]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest execute-action-test
  (testing "execute-action"
    (let [executor (executor)
          session (session/create {:executor executor
                                   :recorder (in-memory-recorder)})]
      (execute-action session {:a 1})
      (is (= [{:target nil :user nil :action {:a 1}}] (plan executor))
          "calls the executor")
      (is (= [{:target nil :user nil :action {:a 1}}]
             (results (recorder session)))
          "records the results"))))
