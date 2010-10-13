(ns pallet.crate.splunk-test
  (:use pallet.crate.splunk
        clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.compute.jclouds :as jclouds]))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (jclouds/make-node "tag" :id "id")]
       (splunk)
       (configure))))
