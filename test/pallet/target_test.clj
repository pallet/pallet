(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test)
  (:require
   [pallet.common.logging.logutils :as logutils]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest os-family-test
  (is (= :ubuntu (os-family {:os-family :ubuntu}))))
