(ns pallet.resource-test
  (:use pallet.resource)
  (:require
   [clojure.contrib.string :as string]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test))

(use-fixtures :once (test-utils/console-logging-threshold))

(defmacro is-phase
  [session phase]
  `(do
     (is (= ~phase (:phase ~session)))
     ~session))

(deftest execute-after-phase-test
  (is (= :fred
         (:phase
          (execute-after-phase
           {:phase :fred}
           (is-phase :pallet.phase/post-fred))))))

(deftest execute-pre-phase-test
  (is (= :fred
         (:phase
          (execute-pre-phase
           {:phase :fred}
           (is-phase :pallet.phase/pre-fred))))))

(test-utils/with-console-logging-threshold :error
  (defresource test-resource (f [session arg] (name arg)))
  (defaggregate test-resource (f [session arg] (string/join "" arg)))
  (defcollect test-resource (f [session arg] arg))
  (deflocal test-resource (f [session arg] arg)))

(deftest phase-test
  (is (fn? (phase identity))))
