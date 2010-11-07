(ns pallet.crate.nagios-config-test
  (:use pallet.crate.nagios-config)
  (:require
   [pallet.parameter :as parameter]
   [pallet.request-map :as request-map]
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.crate.nagios :as nagios]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest service*-test
  (let [cfg {:service-group "g" :service-description "d" :command "c"}
        node (test-utils/make-node "tag" :id "id")
        request {:target-node node}
        host-id (keyword (format "tag%s" (request-map/safe-id "id")))]
    (is (= [cfg]
             (-> (service request cfg)
                 :parameters :nagios :host-services host-id)))))

(deftest command-test
  (let [cfg {:command_name "n" :command_line "c"}
        node (test-utils/make-node "tag")
        request {:target-node node}]

    (is (= {:n "c"}
           (-> (apply command request (apply concat cfg))
               :parameters :nagios :commands)))))

(deftest invoke-test
  (is (test-utils/build-resources
       [:target-node (test-utils/make-node "tag" :id "id")]
       ;; without server
       (nrpe-client)
       (nrpe-client-port)
       ;; with server
       (nagios/nagios "pwd")
       (nrpe-client)
       (nrpe-client-port))))
