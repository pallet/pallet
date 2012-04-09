(ns pallet.operation.primitives-test
  (:use
   clojure.test
   pallet.operation.primitives
   [pallet.compute.node-list :only [make-node node-list-service]]
   [pallet.core :only [group-spec]]
   [pallet.core.api :only [query-nodes]]
   [pallet.operate :only [execute-operation status report-operation]]
   [pallet.operations :only [operations operation]]))

(deftest available-nodes-test
  ;; (testing "fsm config"
  ;;   (let [config (available-nodes)]
  ;;     (is (map? config))))
  (testing "operation"
    (let [operations (operations
                       (operation list-nodes [compute groups]
                         [node-groups (available-nodes compute groups)]
                         node-groups))
          ;; build a compute service
          [n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service
                   [n1 n2]
                   :environment {:operations operations})
          op (execute-operation (operations 'list-nodes) [service [g1]])]
      (is op)
      (is (instance? pallet.operate.Operation op))
      ;; (clojure.pprint/pprint (status op))
      ;; (Thread/sleep 1000)
      (report-operation op)
      (is (= (query-nodes service [g1]) @op))
      (report-operation op))))
