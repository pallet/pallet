(ns pallet.core.api-test
  (:require
   [pallet.node :as node])
  (:use
   clojure.test
   [pallet.compute.node-list :only [make-node node-list-service]]
   [pallet.core :only [group-spec]]
   pallet.core.api))

(deftest query-nodes-test
  (testing "default groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service [n1 n2])]
      (is (= [{:node n1 :groups [g1]}
              {:node n2 :groups [g1]}]
               (query-nodes service [g1])))))
  (testing "custom groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec
              :g1
              :node-predicate #(= "192.168.1.2" (node/primary-ip %)))
          service (node-list-service [n1 n2])]
      (is (= [{:node n1 :groups ()}
              {:node n2 :groups [g1]}]
               (query-nodes service [g1]))))))
