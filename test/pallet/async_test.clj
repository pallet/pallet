(ns pallet.async-test
  (:require
   [clojure.core.async :refer [chan <!!]]
   [clojure.test :refer :all]
   [pallet.async :refer :all]))

(deftest timeout-chan-test
  (let [c (chan)
        t (timeout-chan c 100)]
    (println "t" t)
    (is (nil? (first (<!! t))))
    (println "test done")))
