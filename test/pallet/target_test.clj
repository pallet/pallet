(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test
        pallet.test-utils))


(deftest os-family-test
  (is (= :ubuntu (os-family {:os-family :ubuntu}))))

