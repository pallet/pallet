(ns pallet.core.session-test
  (:require
   [clojure.core.typed
    :refer [check-ns fn>]]
   [clojure.test :refer :all]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.core.session :refer [create]]
   [pallet.core.types :refer [Session Action ActionResult]]))

(deftest create-test
  (is (create {:executor (fn> [session :- Session action :- Action]
                           {:exit 0})
               :execute-status-fn (fn> [r :- ActionResult] r)
               :plan-state (in-memory-plan-state {})
               :recorder (in-memory-recorder)})))
