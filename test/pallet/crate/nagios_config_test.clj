(ns pallet.crate.nagios-config-test
  (:use pallet.crate.nagios-config)
  (:require
   [pallet.compute.jclouds :as jclouds]
   [pallet.parameter :as parameter]
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.crate.nagios :as nagios])
  (:use
   clojure.test
   pallet.test-utils))

(deftest service*-test
  (let [cfg {:service-group "g" :service-description "d" :command "c"}
        node (jclouds/make-node "tag" :id "id")
        request {:target-node node}]
    (is (= [cfg]
             (-> (service request cfg)
                 :parameters :nagios :host-services :host-tag-id)))))

(deftest command-test
  (let [cfg {:command_name "n" :command_line "c"}
        node (jclouds/make-node "tag")
        request {:target-node node}]

    (is (= {:n "c"}
           (-> (apply command request (apply concat cfg))
               :parameters :nagios :commands)))))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (jclouds/make-node "tag" :id "id")]
       ;; without server
       (nrpe-client)
       (nrpe-client-port)
       ;; with server
       (nagios/nagios "pwd")
       (nrpe-client)
       (nrpe-client-port))))
