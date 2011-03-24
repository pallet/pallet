(ns pallet.crate.network-service-test
  (:use clojure.test)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.crate.network-service :as network-service]
   [pallet.test-utils :as test-utils]))

(deftest wait-for-port-listen-test
  (is
   (first
    (build-actions/build-actions
     {}
     (network-service/wait-for-port-listen 80)))))

(deftest wait-for-http-status-test
  (is
   (first
    (build-actions/build-actions
     {}
     (network-service/wait-for-http-status "http://localhost/" 200)))))
