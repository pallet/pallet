(ns pallet.task.converge-test
  (:require
   [clojure.test :refer :all]
   [pallet.api :refer [group-spec lift]]
   [pallet.task.converge :refer :all]
   [pallet.test-utils :refer :all]))

(def a (group-spec "a"))
(def b (group-spec "b"))

(with-private-vars [pallet.task.converge [build-args]]
  (deftest build-args-test
    (is (= [{a 1} :phase []]
           (build-args ["pallet.task.converge-test/a" "1"])))
    (is (= [{a 1 b 2} :phase []]
           (build-args ["pallet.task.converge-test/a" "1"
                        "pallet.task.converge-test/b" "2"])))
    (is (= [{a 1} :phase [:b]]
           (build-args ["pallet.task.converge-test/a" "1" ":b"])))
    (is (= [{a 1} :prefix "a" :phase [:b]]
           (build-args ["a" "pallet.task.converge-test/a" "1" ":b"])))))
