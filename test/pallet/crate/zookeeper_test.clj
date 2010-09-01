(ns pallet.crate.zookeeper-test
  (:use pallet.crate.zookeeper)
  (:use
   clojure.test
   pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest zookeeper-test
  (is ; just check for compile errors for now
   (test-resource-build
    [nil {:image [:ubuntu]}]
    (install))))
