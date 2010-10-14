(ns pallet.crate.iozone-test
  (:use pallet.crate.iozone)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script]
   [pallet.target :as target]
   [pallet.template :as template]
   [pallet.utils :as utils])
  (:use clojure.test
        pallet.test-utils))

(deftest invoke-test
  (is
   (build-resources
    []
    (iozone))))
