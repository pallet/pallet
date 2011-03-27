(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test)
  (:require
   [pallet.test-utils :as test-utils]))

(use-fixtures :once (test-utils/console-logging-threshold))

(deftest os-family-test
  (is (= :ubuntu (os-family {:os-family :ubuntu}))))
