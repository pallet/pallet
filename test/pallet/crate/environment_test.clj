(ns pallet.crate.environment-test
  (:use pallet.crate.environment)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]))

(deftest service-test
  (is
   (first (build-actions/build-actions
           {}
           (system-environment "testenv" {"A" 1 :B "b"})))))
