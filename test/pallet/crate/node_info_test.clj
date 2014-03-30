(ns pallet.crate.node-info-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.plan :refer [execute-plan]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.crate.node-info :refer [node-info os]]
   [pallet.session :as session :refer [executor plan-state recorder]]
   [pallet.user :as user]))

(use-fixtures :once (logging-threshold-fixture))

(deftest localhost-os-test
  (let [session (session/create {:executor (ssh/ssh-executor)
                                 :plan-state (in-memory-plan-state)
                                 :user user/*admin-user*})
        result (execute-plan session {:node (localhost)} os)]
    (is (map? result))
    (is (= 2 (count (:action-results result))))
    (is (map? (node-info session {:node (localhost)}))
        "The os phase updates the plan-state with the discovered os details")))
