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

(use-fixtures :each reset-default-parameters)

(deftest service*-test
  (let [cfg {:service-group "g" :service-description "d" :command "c"}]
    (target/with-target (compute/make-node "tag") {}
      (service* cfg)
      (parameter/with-parameters [:default]
        (is (= [cfg] (parameter/get-for [:nagios :host-services :tag])))))))
