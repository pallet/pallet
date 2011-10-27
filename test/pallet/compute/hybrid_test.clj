(ns pallet.compute.hybrid-test
  (:require
   [pallet.compute.hybrid :as hybrid]
   [pallet.compute.jclouds :as jclouds]
   [pallet.compute.jclouds-ssh-test :as ssh-test]
   [pallet.compute.jclouds-test-utils :as jclouds-test-utils]
   [pallet.compute.node-list :as node-list]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.utils :as utils])
  (:use
   clojure.test)
  (:import
   pallet.compute.node_list.Node))

(def *compute-service* ["stub" "" "" ])

(use-fixtures
  :each
  (jclouds-test-utils/compute-service-fixture
   *compute-service*
   :extensions
   [(ssh-test/ssh-test-client ssh-test/no-op-ssh-client)]))

(deftest supported-providers-test
  (is (hybrid/supported-providers)))

(deftest service-test
  (is (instance?
       pallet.compute.ComputeService
       (compute/compute-service "hybrid")))
  (is (instance?
       pallet.compute.hybrid.HybridService
       (compute/compute-service "hybrid"))))

(deftest nodes-test
  (let [node-1 (node-list/make-node "n1" "t" "1.2.3.4" :ubuntu)
        node-2 (node-list/make-node "n2" "t" "1.2.3.5" :ubuntu)
        node-list-1 (compute/compute-service "node-list" :node-list [node-1])
        node-list-2 (compute/compute-service "node-list" :node-list [node-2])
        hybrid (compute/compute-service
                "hybrid" :sub-services {:nl1 node-list-1 :nl2 node-list-2})]
    (is (= [node-1 node-2] (compute/nodes hybrid))
        "return nodes from both sub-services")))

(deftest close-test
  (is (= [] (compute/close (compute/compute-service "hybrid")))))

(deftest start-node-test
  (jclouds-test-utils/purge-compute-service)
  (let [jc (jclouds-test-utils/compute)
        nl (compute/compute-service "node-list")
        gs (core/group-spec :gs)]
    (let [hybrid (compute/compute-service
                  "hybrid" :sub-services {:jc jc :nl nl})]
      (is (thrown? RuntimeException
                   (compute/run-nodes hybrid gs 1 utils/*admin-user* "" nil)))
      "throw if group is not dispatched to a service")
    (let [hybrid (compute/compute-service
                  "hybrid" :sub-services {:jc jc :nl nl}
                  :groups-for-services {:jc #{:gs}})]
      (is (= 1
             (count (compute/run-nodes hybrid gs 1 utils/*admin-user* "" nil)))
          "Starts a node in a mapped group")
      (is (= 1 (count (filter compute/running? (compute/nodes hybrid))))
          "Starts a node in a mapped group")
      (compute/destroy-node
       hybrid (first (filter compute/running? (compute/nodes hybrid))))
      (is (= 0 (count (filter compute/running? (compute/nodes hybrid))))
          "destroys a node")
      (is (= 1
             (count (compute/run-nodes hybrid gs 1 utils/*admin-user* "" nil)))
          "Starts a node in a mapped group")
      (compute/destroy-nodes-in-group hybrid :gs)
      (is (= 0 (count (filter compute/running? (compute/nodes hybrid))))
          "destroys a group"))))
