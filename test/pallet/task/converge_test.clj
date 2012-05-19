(ns pallet.task.converge-test
  (:use pallet.task.converge)
  (:use
   clojure.test
   pallet.test-utils
   [pallet.api :only [group-spec lift]]))


(with-private-vars [pallet.task.converge [build-args]]
  (deftest build-args-test
    (let [a (group-spec "a")
          b (group-spec "b")]
      (is (= [{a 1} :phase []] (build-args [a 1])))
      (is (= [{a 1 b 2} :phase []] (build-args [a 1 b 2])))
      (is (= [{a 1} :phase [:b]] (build-args [a 1 :b])))
      (is (= [{a 1} :prefix "a" :phase [:b]] (build-args ['a a 1 :b]))))))
