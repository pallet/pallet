(ns pallet.action-options-test
  (:require
   [clojure.test :refer :all]
   [pallet.action-options :refer :all]
   [pallet.core.executor.plan :as plan]
   [pallet.session :as session]))

(deftest with-action-options-tets
  (let [session (session/create {:executor (plan/plan-executor)})
        m {:kw 1}]
    (is (empty? (action-options session)))
    (with-action-options session m
      (is (= m (action-options session))))
    (is (empty? (action-options session)))))
