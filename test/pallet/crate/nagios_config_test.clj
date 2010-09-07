(ns pallet.crate.nagios-config-test
  (:use pallet.crate.nagios-config)
  (:require
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.target :as target]
   [pallet.crate.nagios-config :as nagios-config])
  (:use
   clojure.test
   pallet.test-utils))

(deftest service*-test
  (let [cfg {:service-group "g" :service-description "d" :command "c"}
        node (compute/make-node "tag" :id "id")
        request {:target-node node}]
    (is (= [cfg]
             (-> (service request cfg)
                 :parameters :nagios :host-services :host-tag-id)))))

(deftest command-test
  (let [cfg {:command_name "n" :command_line "c"}
        node (compute/make-node "tag")
        request {:target-node node}]

    (is (= {:n "c"}
           (-> (apply command request (apply concat cfg))
               :parameters :nagios :commands)))))
