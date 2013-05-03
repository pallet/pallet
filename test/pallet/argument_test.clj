(ns pallet.argument-test
  (:require
   [clojure.test :refer :all]
   [pallet.argument :refer :all]))

(deftest evaluate-test
  (is (= "xx" (evaluate "xx" {}))))

(deftest computed-test
  (is (= "xx" (evaluate (delayed-fn (fn [_] "xx")) {}))))

(deftest computed-test
  (is (not= "xx" (delayed [_] "xx")))
  (is (= "xx" (evaluate (delayed [_] "xx") {}))))
