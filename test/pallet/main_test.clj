(ns pallet.main-test
  (:use pallet.main)
  (:require
   [clojure.string :as string]
   [pallet.common.logging.logutils :as logutils])
  (:use
   clojure.test
   pallet.test-utils))

(deftest parse-as-qualified-symbol-test
  (is (nil? (parse-as-qualified-symbol "a")))
  (is (nil? (parse-as-qualified-symbol "a.b")))
  (is (= ['a.b 'a.b/c] (parse-as-qualified-symbol "a.b/c"))))

(deftest map-and-resolve-symbols-test
  (is (= {'pallet.main-test-ns/x 1}
         (reduce map-and-resolve-symbols {}
                 ["a" "b" "1" "pallet.main-test-ns/x"])))
  (is (= 1 (var-get (find-var 'pallet.main-test-ns/x)))))

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
  (testing "help"
    (is (with-output [#"(?s)Pallet is a.*" no-err]
          (is (= 0 (pallet-task ["help"]))))))
  (testing "invalid task"
    (is (with-output [no-out #"(?s)some-non-existing-task is not a task.*"]
          (is (= 1 (pallet-task ["some-non-existing-task"])))))))

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
