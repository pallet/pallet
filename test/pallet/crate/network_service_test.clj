(ns pallet.crate.network-service-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.network-service :as network-service]))

(use-fixtures :once (logging-threshold-fixture))

(deftest wait-for-port-listen-test
  (is
   (re-find
    #"netstat -lnt"
    (first
     (build-actions/build-actions
      {}
      (network-service/wait-for-port-listen 80)))))
  (doseq [[protocol switch] [[:raw "w"] [:tcp "t"] [:udp "u"] [:udplite "U"]]]
    (is
     (re-find
      (re-pattern (format "netstat -ln%s" switch))
      (first
       (build-actions/build-actions
        {}
        (network-service/wait-for-port-listen 80 :protocol protocol)))))))

(deftest wait-for-http-status-test
  (is
   (first
    (build-actions/build-actions
     {}
     (network-service/wait-for-http-status "http://localhost/" 200))))
  (re-find
   #"-b 'x=y'"
   (first
    (build-actions/build-actions
     {}
     (network-service/wait-for-http-status
      "http://localhost/" 200 :cookie "x=y"))))
  (re-find
   #"--header 'Cookie: x=y'"
   (first
    (build-actions/build-actions
     {}
     (network-service/wait-for-http-status
      "http://localhost/" 200 :cookie "x=y")))))
