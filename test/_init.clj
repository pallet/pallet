(ns -init
  "Initialise tests"
  (:require
   [clojure.test :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.plan :refer [phase-errors throw-phase-errors]]
   [pallet.crate :refer [admin-user]]
   [pallet.script.lib :refer [file state-root]]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest initialise
  (testing "Initialise the /var/lib/pallet tree"
    (let [compute (make-localhost-compute :group-name "local")
          op (lift
              (group-spec "local")
              :phase (plan-fn
                      (with-action-options {:script-prefix :sudo}
                        (directory (fragment (file (state-root) "pallet"))
                                   :owner (:username (admin-user))
                                   :recursive false)))
              :compute compute
              :async true)
          session @op]
      (is (nil? (phase-errors @op)))
      (throw-phase-errors @op))))
