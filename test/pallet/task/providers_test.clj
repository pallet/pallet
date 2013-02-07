(ns pallet.task.providers-test
  (:use
   clojure.test
   pallet.task.providers
   pallet.test-utils))

(deftest providers-output-test
  (let [out (with-out-str (providers))]
    (is (.contains out "node-list"))))
