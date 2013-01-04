(ns pallet.debug-test
  (:require
   [clojure.string :as string]
   [pallet.common.logging.logutils :as logutils]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test
   pallet.debug
   [pallet.core.session :only [with-session]]))

(deftest print-session-test
  (let [m {:a 1 :b "2"}]
    (testing "default format string"
      (is (= (pr-str m)
             (with-session m
               (string/trim (with-out-str (print-session))))))
      (is (nil? (test-utils/suppress-output
                 (with-session m (print-session))))))
    (testing "explicit format string"
      (is (= (format "abc %s\n" (pr-str m))
             (with-out-str (with-session m (print-session "abc %s")))))
      (is (nil?
           (test-utils/suppress-output
            (with-session m (print-session "abc %s"))))))))

(deftest log-session-test
  (let [m {:a 1 :b "2"}]
    (is (nil? (logutils/suppress-logging
               (with-session m (log-session)))))
    (is (nil? (logutils/suppress-logging
               (with-session m (log-session "%s")))))))

(deftest assertf-test
  (is (thrown-with-msg? AssertionError #"Something 1"
        (assertf false "Something %s" 1))))
