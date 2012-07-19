(ns pallet.main-invoker-test
  (:require
   [pallet.common.logging.logutils :as logutils]
   [pallet.configure :as configure]
   [pallet.main-invoker :as main-invoker]
   [pallet.test-utils :as test-utils]
   pallet.compute.node-list)
  (:use clojure.test))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest compute-environment-test
  (test-utils/redef
   [configure/pallet-config (constantly
                             {:services {:node-list {:provider :node-list}}})]
   (main-invoker/invoke
    {:provider :node-list}
    (fn [options]
      (is (instance? pallet.compute.node_list.NodeList (:compute options)))
      (is (nil? (:environment options))))
    nil))
  (test-utils/redef
   [configure/pallet-config (constantly
                             {:services
                              {:node-list
                               {:provider :node-list
                                :environment {:user
                                              {:username "fred"}}}}})]
   (main-invoker/invoke
    {:profiles [:node-list]}
    (fn [options]
      (is (instance? pallet.compute.node_list.NodeList (:compute options)))
      (is (= "fred" (:username (:user (:environment options))))))
    nil)))
