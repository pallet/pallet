(ns pallet.node-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute.node-list :refer [make-node]]
   [pallet.node :refer [node-address]]))

(deftest node-address-test
  (let [ip "1.2.3.4"]
    (is (= ip (node-address (make-node nil nil ip nil))))
    (is (= ip (node-address
                (make-node nil nil nil nil :id "id" :private-ip ip))))))
