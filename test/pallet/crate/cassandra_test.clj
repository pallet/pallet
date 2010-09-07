(ns pallet.crate.cassandra-test
  (:use pallet.crate.cassandra)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest cassandra-test
  []
  (let [a {:tag :n :image [:ubuntu]}]
    (is (first
         (resource/build-resources
          [:node-type a]
          (from-package)
          (install))))))
