(ns pallet.task.providers-test
  (:require
   [clojure.test :refer :all]
   [pallet.task.providers :refer :all]))

(deftest providers-output-test
  (let [out (with-out-str (providers))]
    (is (.contains out "node-list"))
    (is (.contains out "hybrid"))
    (is (.contains out "localhost"))))
