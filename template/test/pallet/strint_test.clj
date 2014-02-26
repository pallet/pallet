(ns pallet.strint-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.strint :refer :all]
   [pallet.strint :refer :all]
   [pallet.test-utils :refer :all]))

(deftest interpolate-test
  (is (= '("a " a "") (#'pallet.common.strint/interpolate "a ~{a}"))))

(deftest <<-test
  (testing "docstring examples"
    (let [v 30.5
          m {:a [1 2 3]}]
      (is (= "This trial required 30.5ml of solution."
             (<< "This trial required ~{v}ml of solution.")))
      (is (= "There are 30 days in November."
             (<< "There are ~(int v) days in November.")))
      (is (= "The total for your order is $6."
             (<< "The total for your order is $~(->> m :a (apply +))."))))))

(deftest <<!-test
  (testing "runtime docstring examples"
    (let [v 30.5
          m {:a [1 2 3]}]
      (is (= "This trial required 30.5ml of solution."
             (<<! "This trial required ~{v}ml of solution."
                  (capture-values v))))
      (is (= "This trial required 30.5ml of solution."
             (<<! ((fn [] "This trial required ~{v}ml of solution."))
                  (capture-values v))))
      (is (= "There are 30 days in November."
             (<<! ((fn [] "There are ~(int v) days in November."))
                  (capture-values v))))
      (is (= "The total for your order is $6."
             (<<! ((fn [] "The total for your order is $~(->> m :a (apply +))."))
                  (capture-values m)))))
    (is (= "The total for your order is $6."
           (<<! ((fn [] "The total for your order is $~(->> m :a (apply +))."))
                {'m {:a [1 2 3]}})))))
