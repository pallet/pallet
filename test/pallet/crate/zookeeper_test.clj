(ns pallet.crate.zookeeper-test
  (:use pallet.crate.zookeeper)
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.compute.jclouds :as jclouds]
   [pallet.resource :as resource]))

(deftest zookeeper-test
  (is ; just check for compile errors for now
   (build-resources
    [:target-node (jclouds/make-node "tag")
     :node-type {:tag "tag" :image {:os-family :ubuntu}}]
    (install)
    (configure)
    (init))))
