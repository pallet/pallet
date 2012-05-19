(ns pallet.live-test.live-test-test
  (:use clojure.test)
  (:require
   [pallet.live-test :as live-test]
   [pallet.compute :as compute]))

(deftest exclude-images-test
  (let [images [{:a 1 :b 1} {:a 2} {:a 3 :b 1 :c 3}]]
    (is (= [{:a 1 :b 1}] (live-test/exclude-images images [{:a 2} {:a 3}])))
    (is (= [{:a 2}] (live-test/exclude-images images [{:b 1}])))
    (is (= [{:a 1 :b 1} {:a 2}]
             (live-test/exclude-images images [{:a 3 :b 1 :c 3}])))))

(deftest filter-images-test
  (let [images [{:a 1 :b 1} {:a 2} {:a 3 :b 1 :c 3}]]
    (is (= [{:a 2} {:a 3 :b 1 :c 3}]
             (live-test/filter-images images [{:a 2} {:a 3}])))
    (is (= [{:a 2}] (live-test/filter-images images [{:a 2}])))
    (is (= [{:a 1 :b 1} {:a 3 :b 1 :c 3}]
             (live-test/filter-images images [{:b 1}])))))
