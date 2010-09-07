(ns pallet.argument-test
  (:use pallet.argument)
  (:use clojure.test))

(deftest evaluate-test
  (is (= "xx" (evaluate "xx" {}))))

(deftest computed-test
  (is (= "xx" (evaluate (delayed-fn (fn [_] "xx")) {}))))

(deftest computed-test
  (is (not= "xx" (delayed [_] "xx")))
  (is (= "xx" (evaluate (delayed [_] "xx") {}))))
