(ns pallet.node-test
  (:use
    clojure.test
    [pallet.node :only [node-address]]
    [pallet.compute.node-list :only [make-node]]))

(deftest node-address-test
  (let [ip "1.2.3.4"]
    (is (= ip (node-address (make-node nil nil ip nil))))
    (is (= ip (node-address
                (make-node nil nil nil nil :id "id" :private-ip ip))))))
