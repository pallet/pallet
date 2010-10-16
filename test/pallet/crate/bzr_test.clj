(ns pallet.crate.bzr-test
  (:use pallet.crate.bzr)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest bzr-test
  []
  (is (= (first
          (build-resources
           []
           (package/package "bzr")
           (package/package "bzrtools")))
         (first
          (build-resources
           []
           (bzr))))))
