(ns pallet.crate.splunk-test
  (:use pallet.crate.splunk
        clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.compute :as compute]))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (compute/make-node "tag" :id "id")]
       (splunk)
       (configure))))
