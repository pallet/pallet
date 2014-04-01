(ns pallet.core.executor.ssh-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.plan :refer [execute-plan]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.user :as user]))

(use-fixtures :once (logging-threshold-fixture))

(defn test-session []
  (->
   (session/create {:executor (ssh/ssh-executor)})
   (set-user user/*admin-user*)))

(deftest execute-localhost-test
  (let [session (test-session)
        plan (fn [session]
               (exec-script* session "ls")
               :rv)
        result (execute-plan session (localhost) plan)]
    (is (map? result))
    (is (= 1 (count (:action-results result))))
    (is (every? #(zero? (:exit %)) (:action-results result)))
    (is (= :rv (:return-value result)))))
