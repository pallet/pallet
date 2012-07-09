(ns pallet.compute.hybrid-test
  (:require
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute.hybrid :as hybrid]
   [pallet.compute.node-list :as node-list]
   [pallet.configure :as configure]
   [pallet.compute :as compute]
   [pallet.utils :as utils])
  (:use
   clojure.test)
  (:import
   pallet.compute.node_list.Node))


(use-fixtures :once (logutils/logging-threshold-fixture))

(use-fixtures
 :once (logutils/logging-threshold-fixture))

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
    (is (= [(assoc node-2 :service node-list-2)
            (assoc node-1 :service node-list-1)]
           (compute/nodes hybrid))
        "return nodes from both sub-services")))

(deftest declarative-test
  (let [node-1 ["n1" "t" "1.2.3.4" :ubuntu]
        node-2 ["n2" "t" "1.2.3.5" :ubuntu]
        prov-1 {:provider "node-list"
                :node-list [node-1]}
        prov-2 {:provider "node-list"
                :node-list [node-2]}
        hybrid (configure/compute-service-from-map
                {:provider "hybrid"
                 :sub-services {:prov-1 prov-1
                                :prov-2 prov-2}})]
    (is (= 2 (count (compute/nodes hybrid)))
        "return nodes from both sub-services")))

(deftest close-test
  (is (= [] (compute/close (compute/compute-service "hybrid")))))

;; (deftest start-node-test
;;   (jclouds-test-utils/purge-compute-service)
;;   (let [jc (jclouds-test-utils/compute)
;;         nl (compute/compute-service "node-list")
;;         gs (group-spec :gs)]
;;     (let [hybrid (compute/compute-service
;;                   "hybrid" :sub-services {:jc jc :nl nl})]
;;       (is (thrown? RuntimeException
;;                    (compute/run-nodes hybrid gs 1 *admin-user* "" nil)))
;;       "throw if group is not dispatched to a service")
;;     (let [hybrid (compute/compute-service
;;                   "hybrid" :sub-services {:jc jc :nl nl}
;;                   :groups-for-services {:jc #{:gs}})]
;;       (is (= 1
;;              (count (compute/run-nodes hybrid gs 1 *admin-user* "" nil)))
;;           "Starts a node in a mapped group")
;;       (is (= 1 (count (filter compute/running? (compute/nodes hybrid))))
;;           "Starts a node in a mapped group")
;;       (compute/destroy-node
;;        hybrid (first (filter compute/running? (compute/nodes hybrid))))
;;       (is (= 0 (count (filter compute/running? (compute/nodes hybrid))))
;;           "destroys a node")
;;       (is (= 1
;;              (count (compute/run-nodes hybrid gs 1 *admin-user* "" nil)))
;;           "Starts a node in a mapped group")
;;       (compute/destroy-nodes-in-group hybrid :gs)
;;       (is (= 0 (count (filter compute/running? (compute/nodes hybrid))))
;;           "destroys a group"))))
