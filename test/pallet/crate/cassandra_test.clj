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
  (let [a {:tag :n :image {:os-family :ubuntu}}]
    (is (first
         (build-resources
          [:node-type a]
          (from-package)
          (install))))))
