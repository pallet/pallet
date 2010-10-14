(ns pallet.crate.couchdb-test
  (:use pallet.crate.couchdb)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest couchdb-test
  []
  (testing "invocation"
    (is (build-resources
         []
         (install)
         (configure {})
         (configure {[:a :b] "value"})
         (couchdb)))))
