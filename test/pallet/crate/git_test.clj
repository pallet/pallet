(ns pallet.crate.git-test
  (:use pallet.crate.git)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package])
  (:use clojure.test
        pallet.test-utils))

(deftest git-test
  []
  (let [a {:tag :n :image {:packager :aptitude}}]
    (is (= (first
            (build-resources
             [:node-type a]
             (package/package "git-core")
             (package/package "git-email")))
           (first
            (build-resources
             [:node-type a]
             (git))))))
  (let [a {:tag :n :image {:packager :yum}}]
    (is (= (first
            (build-resources
             [:node-type a]
             (package/package "git")
             (package/package "git-email")))
           (first
            (build-resources
             [:node-type a]
             (git)))))))
