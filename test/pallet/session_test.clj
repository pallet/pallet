(ns pallet.session-test
  (:require
   [clojure.core.typed
    :refer [check-ns fn>]]
   [clojure.test :refer :all]
   [pallet.core.executor.ssh :refer [ssh-executor]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.types :refer [Session Action ActionResult]]
   [pallet.session :refer [create]]))

(deftest create-test
  (is (create {:executor  (ssh-executor)
               :plan-state (in-memory-plan-state {})
               :recorder (in-memory-recorder)})))
