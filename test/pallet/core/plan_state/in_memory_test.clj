(ns pallet.core.plan-state.in-memory-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.plan-state :refer :all]
   [pallet.core.plan-state.in-memory :as in-memory]
   [pallet.core.plan-state-test :refer [plan-state-read-write-test]]
   [simple-check.core :as simple-check]))

(deftest get-scope-test
  (testing "a plan-state with a host path"
    (let [s (in-memory/in-memory-plan-state {:host {:h {:p :a}}})]
      (is (= :a (get-scope s :host :h [:p]))
          "should lookup a single scope and path correctly")
      (is (nil? (get-scope s :host :h [:q]))
          "should return nil for an unspecified path")
      (is (= ::default (get-scope s :host :h [:q] ::default))
          "should return default for an unspecified path"))))

(deftest get-scopes-test
  (testing "a plan-state with a host path"
    (let [s (in-memory/in-memory-plan-state {:host {:h {:p :a}}})]
      (is (= {[:host :h] :a} (get-scopes s {:host :h} [:p]))
          "should lookup the nested path correctly")
      (is (= {} (get-scopes s {:group :g} [:p]))
          "should not contain scopes that do not provide a value for path")
      (is (= {[:group :g] ::default}
             (get-scopes s {:group :g} [:p] ::default))
          "should return default for scopes that do not provide path")))
  (testing "a plan-state with a host and group path"
    (let [s (in-memory/in-memory-plan-state {:host {:h {:p :a}}
                                             :group {:g {:p :b}}})]
      (is (= {[:host :h] :a [:group :g]:b}
             (into {} (get-scopes s {:host :h :group :g} [:p])))
          "should lookup both paths correctly"))))

(deftest update-scope-test
  (testing "a plan-state with no state"
    (let [s (in-memory/in-memory-plan-state {:host {:h {:p :a}}})]
      (testing "update with a scope value"
        (is (= :a (get-scope s :host :h [:p]))
            "should all get of new value")))))
