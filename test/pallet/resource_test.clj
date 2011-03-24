(ns pallet.resource-test
  (:use pallet.resource)
  (:require
   [clojure.contrib.string :as string]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test))

(use-fixtures :once (test-utils/console-logging-threshold))

(defmacro is-phase
  [request phase]
  `(do
     (is (= ~phase (:phase ~request)))
     ~request))

(deftest execute-after-phase-test
  (is (= :fred
         (:phase
          (execute-after-phase
           {:phase :fred}
           (is-phase :after-fred))))))

(deftest execute-pre-phase-test
  (is (= :fred
         (:phase
          (execute-pre-phase
           {:phase :fred}
           (is-phase :pre-fred))))))

(test-utils/with-console-logging-threshold :error
  (defresource test-resource (f [request arg] (name arg)))
  (defaggregate test-resource (f [request arg] (string/join "" arg)))
  (defcollect test-resource (f [request arg] arg))
  (deflocal test-resource (f [request arg] arg)))

(deftest phase-test
  (is (fn? (phase identity))))
