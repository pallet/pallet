(ns pallet.core.operations-test
  (:refer-clojure :exclude [delay])
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :refer [nodes]]
   [pallet.compute.node-list :refer [node-list-service make-localhost-node]]
   [pallet.plan :refer [phase-errors stop-execution-on-error]]
   [pallet.core.operations :refer :all]
   [pallet.core.primitives :refer [async-operation]]
   [pallet.executors :refer [default-executor]]
   [pallet.node :refer [group-name]]
   [pallet.test-utils :refer [make-localhost-compute]]
   [pallet.user :refer [*admin-user*]]))

(use-fixtures :once (logging-threshold-fixture))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(fn []
       (clojure.tools.logging/info (format "Seenfn %s %s" name @seen))
       (is (not @seen))
       (reset! seen true))
     seen?]))

(defn count-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [count-atom (atom 0)
        get-count (fn [] @count-atom)]
    [(fn []
       (clojure.tools.logging/info (format "count-fn %s %s" name @count-atom))
       (swap! count-atom inc))
     get-count]))

(deftest lift-test
  (let [user (assoc *admin-user* :no-sudo true)
        env {:user user
             :algorithms
             {:executor #'default-executor
              :execute-status-fn #'stop-execution-on-error}}
        ps {:ps 1}]
    (testing "lift a phase for a node in a group"
      (let [compute (make-localhost-compute)
            group (group-spec
                      (group-name (first (nodes compute)))
                    :phases {:p (plan-fn (exec-script "ls /"))})
            operation (async-operation {})
            node-set (group-nodes operation compute [group])
            op (lift operation node-set ps env [:p] {})
            {:keys [plan-state results targets]} op]
        (is (nil? (phase-errors op)))
        (is (= 1 (count targets)))
        (is (some
             (partial re-find #"bin")
             (->> (mapcat :result results) (map :out))))
        (is (= ps plan-state))))
    (testing "lift two phases for a node in a group"
      (let [compute (make-localhost-compute)
            [localf seen?] (seen-fn "lift-test")
            group (group-spec
                      (group-name (first (nodes compute)))
                    :phases {:p (plan-fn
                                 (exec-script "ls /"))
                             :p2 (plan-fn
                                  (localf)
                                  (exec-script "ls /"))})
            operation (async-operation {})
            node-set (group-nodes operation compute [group])
            op (lift operation node-set ps env [:p :p2] {})
            {:keys [plan-state results targets]} op]
        (is (not (empty? results)))
        (is (not (phase-errors op)))
        (is (= 1 (count targets)))
        (testing "results"
          (is (= 2 (count results)))
          (is (= #{:p :p2} (set (map :phase results)))))
        (let [r (mapcat :result results)]
          (is (re-find #"bin" (-> r first :out)))
          (is (re-find #"bin" (-> r second :out))))
        (is (= ps plan-state))))
    (testing "lift two phases for two nodes in a group"
      (let [compute (node-list-service
                     [(make-localhost-node) (make-localhost-node)])
            [localf get-count] (count-fn "lift-test")
            group (group-spec
                      (group-name (first (nodes compute)))
                    :phases {:p (plan-fn (exec-script "ls /"))
                             :p2 (plan-fn
                                  (exec-script "ls /")
                                  (localf))})
            operation (async-operation {})
            node-set (group-nodes operation compute [group])
            op (lift operation node-set ps env [:p :p2] {})
            {:keys [plan-state results targets]} op]
        (is (not (phase-errors op)) "operation failed")
        (is (= 2 (count targets)))
        (is (= 2 (get-count)))
        (testing "results"
          (is (= 4 (count results)))
          (is (= #{:p :p2} (set (map :phase results)))))
        (let [r (mapcat :result results)]
          (is (re-find #"bin" (-> r first :out)))
          (is (re-find #"bin" (-> r second :out))))
        (is (= ps plan-state))))))
