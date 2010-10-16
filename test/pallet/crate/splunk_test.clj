(ns pallet.crate.splunk-test
  (:use pallet.crate.splunk
        clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils]))

(deftest invoke-test
  (is (test-utils/build-resources
       [:target-node (test-utils/make-node "tag" :id "id")]
       (splunk)
       (configure))))
