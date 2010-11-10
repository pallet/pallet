(ns pallet.crate.chef-test
  (:require
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils])
  (:use
   pallet.crate.chef
   clojure.test))

(deftest chef-test
  (is ; just check for compile errors for now
   (test-utils/build-resources
    []
    (chef)
    (solo "abc"))))
