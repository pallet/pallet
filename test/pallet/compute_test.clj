(ns pallet.compute-test
  (:use [pallet.compute] :reload-all)
  (require
   [pallet.utils]
   [org.jclouds.compute :as jclouds])
  (:use clojure.test
        pallet.test-utils)
  (:import [org.jclouds.compute.domain NodeState]))

(deftest compute-node?-test
  (is (not (compute-node? 1)))
  (is (compute-node? (make-node "a")))
  (is (every? compute-node? [(make-node "a") (make-node "b")])))

(deftest node-counts-by-tag-test
  (is (= {:a 2}
         (node-counts-by-tag [(make-node "a") (make-node "a")]))))

(deftest running?-test
  (is (not (jclouds/running? (make-node "a" :state NodeState/TERMINATED))))
  (is (jclouds/running? (make-node "a" :state NodeState/RUNNING))))
