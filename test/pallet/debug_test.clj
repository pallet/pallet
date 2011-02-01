(ns pallet.debug-test
  (:require
   [clojure.string :as string])
  (:use
   clojure.test
   pallet.debug))

(deftest print-request-test
  (let [m {:a 1 :b "2"}]
    (testing "default format string"
      (is (= (pr-str m) (string/trim (with-out-str (print-request m)))))
      (is (= m (print-request m))))
    (testing "explicit format string"
      (is (= (format "abc %s\n" (pr-str m))
             (with-out-str (print-request m "abc %s"))))
      (is (= m (print-request m "abc %s"))))))

(deftest log-request-test
  (let [m {:a 1 :b "2"}]
    (is (= m (log-request m)))
    (is (= m (log-request m "%s")))))
