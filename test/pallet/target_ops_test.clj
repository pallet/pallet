(ns pallet.target-ops-test
  (:require
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.plan :as plan]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.target-ops :refer :all]
   [pallet.user :as user]
   [schema.core :as schema :refer [validate]]))

(use-fixtures :once (logging-threshold-fixture))
