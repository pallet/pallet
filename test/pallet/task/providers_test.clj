(ns pallet.task.providers-test
  (:use
   clojure.test
   pallet.task.providers
   pallet.test-utils))

(deftest providers-test
  []
  (is (with-out-str (providers nil))))
