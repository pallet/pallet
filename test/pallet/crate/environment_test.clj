(ns pallet.crate.environment-test
  (:use pallet.crate.environment)
  (:use
   clojure.test
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-test
  (is
   (first (build-actions
           {}
           (system-environment "testenv" {"A" 1 :B "b"})))))
