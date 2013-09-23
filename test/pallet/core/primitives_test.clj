(ns pallet.core.primitives-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute.node-list :refer [make-node node-list-service]]
   [pallet.core.api :as api :refer [phase-errors stop-execution-on-error]]
   [pallet.core.primitives :refer :all]
   [pallet.executors :refer [default-executor]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

;; (deftest available-nodes-test
;;   (testing "operation"
;;     (let [list-nodes (fn  [compute groups]
;;                        (dofsm list-nodes
;;                          [node-groups (service-state compute groups)]
;;                          node-groups))
;;           ;; build a compute service
;;           [n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                    (make-node "n2" "g1" "192.168.1.2" :linux)]
;;           g1 (group-spec :g1)
;;           service (node-list-service [n1 n2])
;;           ;; start operation
;;           op (operate (list-nodes service [g1]))]
;;       (is (instance? pallet.algo.fsmop.Operation op))
;;       ;; wait for result
;;       (is (= (api/service-state service [g1]) @op)))))

(deftest execute-phase-test
  (let [service (make-localhost-compute)
        a (atom nil)
        set-a (fn [] [(reset! a true)])
        g (group-spec :local :phases {:p (plan-fn
                                          (set-a)
                                          (exec-script ("ls")))})
        targets (api/service-state service [g])
        op (execute-phase
            targets
            {:ps 1}
            {:algorithms
             {:executor #'default-executor
              :execute-status-fn #'stop-execution-on-error}}
            :p
            targets
            (api/environment-execution-settings))
        [results plan-state] op]
    (is @a)
    (is (= {:ps 1} plan-state))
    (is (sequential? results))
    (is (every? map? results))
    (is (every? map? (mapcat :result results)))
    (is (not (some :error (mapcat :result results))))
    (is (not (some :error results)))
    (is (not (api/phase-errors-in-results results))))
  (testing "exception"
    (let [service (make-localhost-compute)
        g (group-spec :local :phases {:p (plan-fn
                                          (throw (ex-info "error" {:x 1})))})
        targets (api/service-state service [g])
        op (execute-phase
            targets
            {:ps 1}
            {}
            :p
            targets
            (api/environment-execution-settings))
        [results plan-state] op]
    (is (= {:ps 1} plan-state))
    (is (sequential? results))
    (is (every? map? results))
    (is (every? map? (mapcat :result results)))
    (is (every? :error results))
    (is (some :error results))
    (is (api/phase-errors-in-results results)))))
