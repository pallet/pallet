(ns pallet.node-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute.node-list :as node-list :refer [make-node node]]
   [pallet.node :as node :refer [node-address]]))

(deftest node-address-test
  (let [ip "1.2.3.4"]
    (is (= ip (node-address (make-node nil :g ip nil))))
    (is (= ip (node-address
               (make-node nil :g nil nil {:id "id" :private-ip ip}))))
    (is (= ip (node-address (node "mynode" {:ip ip}))))))

(deftest has-base-name?-test
  (is (node/has-base-name?
       (node-list/node "mynode" {:ip "localhost"})
       "mynode"))
  (is (node/has-base-name?
       (node-list/node "mynode" {:ip "localhost"})
       :mynode)))
