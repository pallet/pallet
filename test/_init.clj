(ns -init
  "Initialise tests"
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [setup-node]]
   [pallet.algo.fsmop :refer [failed?]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.crate :refer [admin-user]]
   [pallet.executors :refer [force-target-via-ssh-executor]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest initialise
  (let [compute (make-localhost-compute :group-name "local")
        op (lift
            (group-spec "local")
            :phase (plan-fn
                       (with-action-options
                         {:script-prefix :sudo
                          :script-env-fwd [:TMP :TEMPDIR :TEMP]}
                         (setup-node)))
            :compute compute
            :executor force-target-via-ssh-executor
            :async true)
        session @op]
    (is (not (failed? op)))
    (is (nil? (phase-errors @op)))))
