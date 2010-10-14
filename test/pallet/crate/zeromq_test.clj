(ns pallet.crate.zeromq-test
  (:use pallet.crate.zeromq)
  (:require
   [pallet.resource :as resource])
  (:use clojure.test
        pallet.test-utils))

(deftest invocation
  (is (build-resources
       []
       (install))))
