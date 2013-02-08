(ns pallet.task.converge-test
  (:use pallet.task.converge)
  (:use
   clojure.test
   pallet.test-utils
   [pallet.api :only [group-spec lift]]))

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
