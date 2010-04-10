(ns pallet.compute-test
  (:use [pallet.compute] :reload-all)
  (require [pallet.utils])
  (:use clojure.test
        pallet.test-utils))

(deftest compute-node?-test
  (is (not (compute-node? 1)))
  (is (compute-node? (make-node "a")))
  (is (every? compute-node? [(make-node "a") (make-node "b")])))

(deftest node-counts-by-tag-test
  (is (= {:a 2}
         (node-counts-by-tag [(make-node "a") (make-node "a")]))))
