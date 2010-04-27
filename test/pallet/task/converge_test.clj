(ns pallet.task.converge-test
  (:use [pallet.task.converge] :reload-all)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))


(with-private-vars [pallet.task.converge [build-args]]

  (deftest build-args-test
    (core/defnode a [])
    (core/defnode b [])
    (is (= [{a 1}] (build-args [a 1])))
    (is (= [{a 1 b 2}] (build-args [a 1 b 2])))
    (is (= [{a 1} :b] (build-args [a 1 :b])))
    (is (= ["a" {a 1} :b] (build-args ['a a 1 :b])))))
