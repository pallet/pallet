(ns pallet.crate.rabbitmq-test
  (:use pallet.crate.rabbitmq)
  (:require
   [pallet.resource :as resource])
  (:use clojure.test
        pallet.test-utils))

(deftest invocation
  (is (resource/build-resources
       []
       (rabbitmq :node-count 2)
       (configure
        [{:rabbit [{:cluster_nodes ["rabbit@rabbit1" "rabbit@rabbit2"]}]}]))))
