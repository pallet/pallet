(ns pallet.debug-test
  (:require
   [clojure.string :as string]
   [pallet.common.logging.logutils :as logutils]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test
   pallet.debug))

(deftest print-session-test
  (let [m {:a 1 :b "2"}]
    (testing "default format string"
      (is (= (pr-str m) (string/trim (with-out-str ((print-session) m)))))
      (is (= [nil m] (test-utils/suppress-output ((print-session) m)))))
    (testing "explicit format string"
      (is (= (format "abc %s\n" (pr-str m))
             (with-out-str ((print-session "abc %s") m))))
      (is (= [nil m]
             (test-utils/suppress-output ((print-session "abc %s") m)))))))

(deftest log-session-test
  (let [m {:a 1 :b "2"}]
    (is (= [nil m] (logutils/suppress-logging ((log-session) m))))
    (is (= [nil m] (logutils/suppress-logging ((log-session "%s") m))))))
