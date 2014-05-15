(ns pallet.action.impl-test
  (:require
   [clojure.test :refer :all]
   [pallet.action.impl :refer :all]))

(deftest make-action-test
  (testing "arguments"
    (let [action (make-action 'a0 {})]
      (is (= 'a0 (action-symbol action)))
      (is (= {} (action-options action)))
      (is (map? @(:impls action)))
      (is (empty? @(:impls action))))))
