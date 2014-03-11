(ns pallet.compute-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [suppress-logging]]
   [pallet.compute :refer :all]))

;; (defmulti-os testos [session])
;; (defmethod testos :linux [session] :linux)
;; (defmethod testos :debian [session] :debian)
;; (defmethod testos :rh-base [session] :rh-base)

;; (deftest defmulti-os-test
;;   (is (= :linux (testos {:server {:image {:os-family :arch}}})))
;;   (is (= :rh-base (testos {:server {:image {:os-family :centos}}})))
;;   (is (= :debian (testos {:server {:image {:os-family :debian}}})))
;;   (is (thrown? clojure.lang.ExceptionInfo
;;                (testos {:server {:image {:os-family :unspecified}}}))))

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

(deftest node-spec-test
  (is (= {:image {:image-id "xx"}}
         (node-spec {:image {:image-id "xx"}})))
  (is (= {:hardware {}}
         (node-spec {:hardware {}})))
  (is (= {:location {:subnet-id "subnet-xxxx"}}
         (node-spec {:location {:subnet-id "subnet-xxxx"}})))
  (is (= {:hardware {:hardware-model "xxxx"}}
         (node-spec {:hardware {:hardware-model "xxxx"}})))
  (testing "type"
    (is (= :pallet.compute/node-spec (type (node-spec {:hardware {}}))))))
