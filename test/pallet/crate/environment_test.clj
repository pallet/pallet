(ns pallet.crate.environment-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.environment :refer [system-environment]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-test
  (is
   (first (build-actions {}
            (system-environment "testenv" {"A" 1 :B "b"})))))
