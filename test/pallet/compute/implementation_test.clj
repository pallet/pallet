(ns pallet.compute.implementation-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute.implementation :refer :all]))

(deftest supported-providers-test
  (is (= #{"node-list" "localhost" "hybrid"} (set (supported-providers)))))
