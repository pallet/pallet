(ns pallet.task.converge-test
  (:use pallet.task.converge)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))


(with-private-vars [pallet.task.converge [build-args]]
  (deftest build-args-test
    (let [a (core/node-spec "a")
          b (core/node-spec "b")]
      (is (= [{a 1} :phase []] (build-args [a 1])))
      (is (= [{a 1 b 2} :phase []] (build-args [a 1 b 2])))
      (is (= [{a 1} :phase [:b]] (build-args [a 1 :b])))
      (is (= [{a 1} :prefix "a" :phase [:b]] (build-args ['a a 1 :b]))))))
