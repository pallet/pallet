(ns pallet.actions-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer :all]
   [pallet.algo.fsmop :refer [complete? failed?]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.crate :refer [target-node]]
   [pallet.node :refer [primary-ip]]
   [pallet.test-utils :refer [make-localhost-compute test-username]]))


(use-fixtures :once (logging-threshold-fixture))

(deftest one-node-filter-test
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]}]
    (is (= {:node 2} (one-node-filter role->nodes [:r :r2]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]}]
    (is (= {:node 3} (one-node-filter role->nodes [:r :r2 :r3]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]
                     :r4 [{:node 4}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r :r2 :r3 :r4])))))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

(deftest actions-test
  (let [counter (atom 0)
        ip (atom 0)
        compute (make-localhost-compute :group-name "local")
        op (lift
            (group-spec "local")
            :phase (plan-fn
                     (as-action (swap! counter inc))
                     (as-action (swap! counter inc))
                     (as-action (reset! ip (primary-ip (target-node)))))
            :compute compute
            :user (local-test-user)
            :async true)
        session @op]
    (is (not (failed? op)))
    (is (= 2 @counter))
    (is (= "127.0.0.1" @ip))))
