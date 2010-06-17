(ns pallet.arguments-test
  (:use pallet.arguments :reload-all)
  (:use
   clojure.test
   pallet.test-utils))

(deftest evaluate-test
  (is (= "xx" (evaluate "xx"))))

(deftest computed-test
  (is (= "xx" (evaluate (computed (fn [] "xx"))))))
