(ns pallet.main-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :as logutils]
   [pallet.main :refer :all]
   [pallet.test-utils :refer :all]))


(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls."
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))

(defmacro with-output
  "Tests for matching out and err regexes."
  [[out err] & body]
  `(is (re-matches ~out
        (with-out-str
          (is (re-matches ~err (with-err-str ~@body)))))))

(defmacro with-output-for-debug
  "Provides compatable syntax to `with-output`, without capturing the streams.
   Useful in debugging, when the body tests fail."
  [[out err] & body]
  `(do ~@body))

(def no-out #"")
(def no-err no-out)

(deftest pallet-task-test
  (binding [*exit-process?* false]
    (testing "help"
      (is (with-output [#"(?s)Pallet is a.*" no-err]
            (is (nil? (pallet-task ["help"]))))))
    (testing "invalid task"
      (testing "throws"
        (suppress-err
         (is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"(?s)suppressed exit.*"
                               (pallet-task ["some-non-existing-task"])))))
      (testing "exception has :exit-code"
        (suppress-err
         (try
           (pallet-task ["some-non-existing-task"])
           (catch Exception e
             (is (= 1 (:exit-code (ex-data e)))))))))))

(deftest report-unexpected-exception-test
  (logutils/suppress-logging
   (is (re-find #"hello"
                (with-err-str
                  (#'pallet.main/report-unexpected-exception
                   (Exception. "hello")))))
   (is (re-find #"java.lang.Exception"
                (with-err-str
                  (#'pallet.main/report-unexpected-exception
                   (Exception. "hello")))))))
