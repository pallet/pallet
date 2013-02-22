(ns pallet.compute.implementation-test
  (:use
   clojure.test
   pallet.compute.implementation))

(deftest supported-providers-test
  (is (= #{"node-list" "localhost" "hybrid"} (set (supported-providers)))))
