(ns pallet.compute.node-list-test
  (:require
   [pallet.compute.node-list :as node-list]
   [pallet.compute :as compute])
  (:use
   clojure.test)
  (:import
   pallet.compute.node_list.Node))

(deftest supported-providers-test
  (is (node-list/supported-providers)))

(deftest make-node-test
  (let [nl (atom nil)]
    (is (= (pallet.compute.node_list.Node.
            "n" "t" "1.2.3.4" :ubuntu "10.2" "n-1-2-3-4" 22 "4.3.2.1" false true
            nl)
           (node-list/make-node
            "n" "t" "1.2.3.4" :ubuntu :private-ip "4.3.2.1" :is-64bit false
            :os-version "10.2" :service nl)))))

(deftest service-test
  (is (instance?
       pallet.compute.ComputeService
       (compute/compute-service "node-list" :node-list [])))
  (is (instance?
       pallet.compute.node_list.NodeList
       (compute/compute-service "node-list" :node-list []))))

(deftest nodes-test
  (let [node (node-list/make-node "n" "t" "1.2.3.4" :ubuntu)
        node-list (compute/compute-service "node-list" :node-list [node])]
    (is (= [node] (compute/nodes node-list)))))

(deftest close-test
  (is (nil? (compute/close
             (compute/compute-service "node-list" :node-list [])))))

(deftest make-localhost-node-test
  (let [node (node-list/make-localhost-node)]
    (is (= "127.0.0.1" (compute/primary-ip node)))))
