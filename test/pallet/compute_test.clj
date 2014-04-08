(ns pallet.compute-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [suppress-logging]]
   [pallet.compute :refer :all]
   [schema.core :refer [validate]]))

(deftest schemas-are-loose-test
  (let [input  {:network {:security-group "default"}}
        output (validate NodeSpec input)]
    (is (= input output))))

(deftest network-schema-spec-test
  (let [input1 {:network {:inbound-ports [22 23 24 25]}}
        input2 {:network {:inbound-ports [{:start-port 22
                                           :end-port 25
                                           :protocol "UDP"}]}}
        input3 {:network {:inbound-ports [{:port 80}]}}      ;; unallowed key
        input4 {:network {:inbound-ports [{:end-port 80}]}}] ;; no start-port
    (is (= input1 (validate NodeSpec input1)))
    (is (= input2 (validate NodeSpec input2)))
    (suppress-logging
     (is (thrown? Exception (validate NodeSpec input3)))
     (is (thrown? Exception (validate NodeSpec input4))))))

(deftest qos-schema-spec-test
  (let [input1 {:qos {}}
        input2 {:qos {:spot-price 2}}
        input3 {:qos {:spot-price 2 :enable-monitoring true}}
        input4 {:qos {:enable-monitoring false}}
        input5 {:qos {:i-am-loose true}}]
    (is (= input1 (validate NodeSpec input1)))
    (is (= input2 (validate NodeSpec input2)))
    (is (= input3 (validate NodeSpec input3)))
    (is (= input4 (validate NodeSpec input4)))
    (is (= input5 (validate NodeSpec input5)))))

(deftest node-spec-test
  (is (= {:image {:image-id "xx" :os-family :ubuntu}}
         (node-spec {:image {:image-id "xx" :os-family :ubuntu}})))
  (is (= {:hardware {}}
         (node-spec {:hardware {}})))
  (is (= {:location {:subnet-id "subnet-xxxx"}}
         (node-spec {:location {:subnet-id "subnet-xxxx"}})))
  (is (= {:hardware {:hardware-model "xxxx"}}
         (node-spec {:hardware {:hardware-model "xxxx"}})))
  (testing "type"
    (is (= :pallet.compute/node-spec (type (node-spec {:hardware {}}))))))

(deftest matches-selectors?-test
  (is (matches-selectors?
       #{:x :y} {:node-spec {:image {:image-id "xx" :os-family :ubuntu}}
                 :selectors #{:x}}))
  (is (not (matches-selectors?
            #{:x :y} {:node-spec {:image {:image-id "xx" :os-family :ubuntu}}
                      :selectors #{:z}}))))
