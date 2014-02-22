(ns pallet.node-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute.node-list :refer [make-node node]]
   [pallet.node :refer [node-address]]))

(deftest node-address-test
  (let [ip "1.2.3.4"]
    (is (= ip (node-address (make-node nil :g ip nil))))
    (is (= ip (node-address
               (make-node nil :g nil nil {:id "id" :private-ip ip}))))
    (is (= ip (node-address (node "mynode" {:ip ip}))))))
