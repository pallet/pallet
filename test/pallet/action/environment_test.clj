(ns pallet.action.environment-test
  (:use pallet.action.environment)
  (:use
   [pallet.stevedore :only [script]]
   clojure.test
   pallet.test-utils
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-test
  (is
   (first (build-actions/build-actions
           {}
           (system-environment "testenv" {"A" 1 :B "b"})))))
