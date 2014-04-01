(ns pallet.core.node-test
  (:require
   [clojure.test :refer :all]
   [pallet.node :as node :refer [node-address]]))

(deftest node?-test
  (is (not (node/node? {})))
  (is (node/node? {:id "id"}))
  (is (thrown? Exception (node/validate-node {})))
  (is (node/validate-node {:id "id"})))

(deftest node-address-test
  (let [ip "1.2.3.4"]
    (is (= ip (node-address {:id "id" :primary-ip ip})))
    (is (= ip (node-address {:id "id" :private-ip ip})))
    (is (= ip (node-address {:id "id" :primary-ip ip :private-ip "xx"})))))

;; (deftest has-base-name?-test
;;   (is (node/has-base-name?
;;        (node-list/node "mynode" {:ip "localhost"})
;;        "mynode"))
;;   (is (node/has-base-name?
;;        (node-list/node "mynode" {:ip "localhost"})
;;        :mynode)))
