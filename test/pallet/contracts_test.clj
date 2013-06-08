(ns pallet.contracts-test
  (:require
   [clj-schema.schema :refer [map-schema]]
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [with-log-to-string suppress-logging]]
   [pallet.contracts :refer :all]))

(deftest check-keys-test
  (let [schema (map-schema :strict [[:a] number?])]
    (with-log-to-string []
      (is (thrown? Exception
                   (check-keys {:a 1 :b 2} [:a :b] schema "test")))
      (is (check-keys {:a 1 :b 2}  [:a] schema "test")))))

(deftest schemas-are-loose-test
  (let [input  {:network {:security-group "default"}}
        output (check-node-spec input)]
    (is (= input output))))

(deftest network-schema-spec-test
  (let [input1 {:network {:inbound-ports [22 23 24 25]}}
        input2 {:network {:inbound-ports [{:start-port 22
                                           :end-port 25
                                           :protocol "UDP"}]}}
        input3 {:network {:inbound-ports [{:port 80}]}}      ;; unallowed key
        input4 {:network {:inbound-ports [{:end-port 80}]}}] ;; no start-port
    (is (= input1 (check-node-spec input1)))
    (is (= input2 (check-node-spec input2)))
    (suppress-logging
     (is (thrown? Exception (check-node-spec input3)))
     (is (thrown? Exception (check-node-spec input4))))))

(deftest qos-schema-spec-test
  (let [input1 {:qos {}}
        input2 {:qos {:spot-price 2}}
        input3 {:qos {:spot-price 2 :enable-monitoring true}}
        input4 {:qos {:enable-monitoring false}}
        input5 {:qos {:i-am-loose true}}]
    (is (= input1 (check-node-spec input1)))
    (is (= input2 (check-node-spec input2)))
    (is (= input3 (check-node-spec input3)))
    (is (= input4 (check-node-spec input4)))
    (is (= input5 (check-node-spec input5)))))
