(ns pallet.task.providers-test
  (:use pallet.task.providers)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))

(deftest providers-output-test
  (let [out (with-out-str (providers))]
    (is (.contains out "node-list"))))
