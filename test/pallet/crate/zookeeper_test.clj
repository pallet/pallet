(ns pallet.crate.zookeeper-test
  (:use pallet.crate.zookeeper)
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.compute :as compute]
   [pallet.resource :as resource]))

(deftest zookeeper-test
  (is ; just check for compile errors for now
   (resource/build-resources
    [:target-node (compute/make-node "tag")
     :node-type {:tag "tag" :image [:ubuntu]}]
    (install)
    (configure)
    (init))))
