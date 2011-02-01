(ns pallet.resource.network-service-test
  (:use clojure.test)
  (:require
   [pallet.resource.network-service :as network-service]
   [pallet.test-utils :as test-utils]))

(deftest wait-for-port-listen-test
  (is
   (first
    (test-utils/build-resources
     []
     (network-service/wait-for-port-listen 80)))))

(deftest wait-for-http-status-test
  (is
   (first
    (test-utils/build-resources
     []
     (network-service/wait-for-http-status "http://localhost/" 200)))))
