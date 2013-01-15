(ns pallet.task.converge-test
  (:use pallet.task.converge)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))

(def a (core/group-spec "a"))
(def b (core/group-spec "b"))

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
