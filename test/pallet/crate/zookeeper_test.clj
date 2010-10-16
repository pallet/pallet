(ns pallet.crate.zookeeper-test
  (:use pallet.crate.zookeeper)
  (:use clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils]))

(deftest zookeeper-test
  (is ; just check for compile errors for now
   (test-utils/build-resources
    [:target-node (test-utils/make-node "tag")
     :node-type {:tag "tag" :image {:os-family :ubuntu}}]
    (install)
    (configure)
    (init))))
