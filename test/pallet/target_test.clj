(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test)
  (:require
   [pallet.common.logging.log4j :as log4j]))

(use-fixtures :once (log4j/logging-threshold-fixture))

(deftest os-family-test
  (is (= :ubuntu (os-family {:os-family :ubuntu}))))
