(ns pallet.crate.os-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.plan :refer :all]
   [pallet.core.node-os :refer [node-os]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.crate.os :refer [os]]
   [pallet.session :as session :refer [executor plan-state recorder]]
   [pallet.user :as user]))

(use-fixtures :once (logging-threshold-fixture))

(deftest localhost-os-test
  (let [session (session/create {:executor (ssh/ssh-executor)
                                 :plan-state (in-memory-plan-state)
                                 :user user/*admin-user*})
        result (execute session {:node (localhost)} os)]
    (is (map? result))
    (is (= 2 (count (:action-results result))))
    (is (map? (node-os (localhost) (plan-state session)))
        "The os phase updates the plan-state with the discovered os details")))
