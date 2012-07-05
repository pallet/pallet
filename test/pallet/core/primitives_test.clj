(ns pallet.core.primitives-test
  (:require
   [pallet.core.api :as api])
  (:use
   [clojure.data :only [diff]]
   clojure.test
   pallet.core.primitives
   [pallet.algo.fsmop :only [dofsm operate status report-operation]]
   [pallet.api :only [group-spec]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.compute.node-list :only [make-node node-list-service]]
   [pallet.test-utils :only [clj-action make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest available-nodes-test
  (testing "operation"
    (let [list-nodes (fn  [compute groups]
                       (dofsm list-nodes
                         [node-groups (service-state compute groups)]
                         node-groups))
          ;; build a compute service
          [n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service [n1 n2])
          ;; start operation
          op (operate (list-nodes service [g1]))]
      (is (instance? pallet.algo.fsmop.Operation op))
      ;; wait for result
      (is (= (api/service-state service [g1]) @op)))))

(deftest build-and-execute-phase-test
  (let [service (make-localhost-compute)
        a (atom nil)
        action (clj-action [session] [(reset! a true) session])
        g (group-spec :local :phases {:p (action)})
        targets (api/service-state service [g])
        results @(operate
                  (build-and-execute-phase
                   targets
                   {}
                   {}
                   (api/environment-execution-settings {})
                   targets
                   :p))]
    (is @a)))
