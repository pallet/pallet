(ns pallet.node-test
  (:require
   [clojure.test :refer :all]
   [pallet.node :as node :refer [node-address]]))

(deftest node?-test
  (is (not (node/node? {})))
  (is (node/node? {:id "id" :os-family :ubuntu :packager :apt}))
  (is (thrown? Exception (node/validate-node {})))
  (is (node/validate-node {:id "id" :os-family :ubuntu :packager :apt})))

(deftest node-address-test
  (let [ip "1.2.3.4"
        base-node {:id "id" :os-family :ubuntu :packager :apt}]
    (is (= ip (node-address (assoc base-node :primary-ip ip))))
    (is (= ip (node-address (assoc base-node :private-ip ip))))
    (is (= ip (node-address (assoc base-node
                              :primary-ip ip :private-ip "xx"))))))

;; (deftest has-base-name?-test
;;   (is (node/has-base-name?
;;        (node-list/node "mynode" {:ip "localhost"})
;;        "mynode"))
;;   (is (node/has-base-name?
;;        (node-list/node "mynode" {:ip "localhost"})
;;        :mynode)))
