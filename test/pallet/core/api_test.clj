(ns pallet.core.api-test
  (:require
   [pallet.node :as node])
  (:use
   clojure.test
   [pallet.action :only [clj-action]]
   [pallet.actions :only [exec-script]]
   [pallet.action-plan :only [stop-execution-on-error]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.compute.node-list
    :only [make-node make-localhost-node node-list-service]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.core.api-impl :only [with-script-for-node]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.executors :only [default-executor]]
   pallet.core.api))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-state-test
  (testing "default groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service [n1 n2])]
      (is (= [(assoc g1 :node (assoc n1 :service service))
              (assoc g1 :node (assoc n2 :service service))]
             (service-state service [g1])))))
  (testing "custom groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec
              :g1
              :node-predicate #(= "192.168.1.2" (node/primary-ip %)))
          service (node-list-service [n1 n2])]
      (is (= [(assoc g1 :node (assoc n2 :service service))]
             (service-state service [g1]))))))

;; (deftest service-state-with-nodes-test
;;   (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                     (make-node "n2" "g1" "192.168.1.2" :linux)
;;                     (make-node "n3" "g1" "192.168.1.3" :linux)]
;;         g1 (group-spec :g1)
;;         service (node-list-service [n1 n2])]
;;     (is (= {:node->groups {n1 [g1] n2 [g1]}
;;             :group->nodes {g1 [n1 n2]}}
;;            (service-state-with-nodes {} {g1 [n1 n2]})))
;;     (is (= {:node->groups {n1 [g1] n2 [g1] n3 [g1]}
;;             :group->nodes {g1 [n3 n1 n2]}}
;;            (service-state-with-nodes
;;              {:node->groups {n3 [g1]} :group->nodes {g1 [n3]}}
;;              {g1 [n1 n2]})))))

;; (deftest service-state-without-nodes-test
;;   (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                     (make-node "n2" "g1" "192.168.1.2" :linux)
;;                     (make-node "n3" "g1" "192.168.1.3" :linux)]
;;         g1 (group-spec :g1)
;;         service (node-list-service [n1 n2 n3])
;;         ss (service-state service [g1])]
;;     (is (= {:node->groups {n1 [g1] n2 [g1]}
;;             :group->nodes {g1 [n1 n2]}}
;;            (service-state-without-nodes ss {g1 {:nodes [n3]}})))))

(deftest group-deltas-test
  (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                 (make-node "n2" "g1" "192.168.1.2" :linux)]
        g1 (group-spec :g1 :count 1)
        service (node-list-service [n1 n2])]
    (is (= {g1 {:actual 2 :target 1 :delta -1}}
           (group-deltas (service-state service [g1]) [g1])))))

(deftest nodes-to-remove-test
  (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                 (make-node "n2" "g1" "192.168.1.2" :linux)]
        g1 (group-spec :g1 :count 1)
        service (node-list-service [n1 n2])
        service-state (service-state service [g1])]
    (is (= {g1 {:nodes [(assoc g1 :node (assoc n1 :service service))]
                :all false}}
           (nodes-to-remove
            service-state
            (group-deltas service-state [g1]))))))

(deftest action-plan-test
  (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
        g1 (group-spec :g1)
        service (node-list-service [n1])
        service-state (service-state service [g1])
        [r plan-state] (with-script-for-node n1
                         ((action-plan
                           service-state {}
                           (plan-fn (exec-script "ls"))
                           {:server {:node n1}})
                          {:ps 1}))]
    (is (seq r))
    (is (map? plan-state))
    (is (= {:ps 1} plan-state))))

(deftest action-plans-test
  (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
        g1 (group-spec
            :g1
            :phases {:p (plan-fn (exec-script "ls"))
                     :g (plan-fn ((clj-action [session] 1)))})
        service (node-list-service [n1])
        n1 (assoc n1 :service service)
        service-state (service-state service [g1])]
    (testing "nodes"
      (let [[r plan-state] ((action-plans
                             service-state {} :p service-state) {:ps 1})
            r1 (first r)]
        (is (seq r))
        (is (map? plan-state))
        (is (= {:ps 1} plan-state))
        (is (= 1 (count r)))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :p (:phase r1)))
        (is (= (assoc g1 :node n1) (:target r1)))))
    (testing "nodes"
      (let [[r plan-state] ((action-plans service-state {} :p service-state) {})
            r1 (first r)]
        (is (seq r))
        (is (= 1 (count r)))
        (is (map? plan-state))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :p (:phase r1)))
        (is (= (assoc g1 :node n1) (:target r1)))))
    (testing "group"
      (let [[r plan-state] ((action-plans
                             service-state {} :g
                             [(assoc g1 :target-type :group)])
                            {})
            r1 (first r)]
        (is (seq r))
        (is (= 1 (count r)))
        (is (map? plan-state))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :g (:phase r1)))
        (is (= (assoc g1 :target-type :group) (:target r1)))
        (is (= :group (-> r1 :target :target-type)))))))

(deftest execute-action-plan-test
  (let [n1 (make-localhost-node :group "g1")
        ga (clj-action [session] [1 session])
        g1 (group-spec
            :g1
            :phases {:p (plan-fn (exec-script "ls"))
                     :g (plan-fn (ga))})
        service (node-list-service [n1])
        service-state (service-state service [g1])]
    (testing "nodes"
      (let [[r plan-state] ((action-plans service-state {} :p service-state)
                            {:ps 1})
            action-plan (first r)

            {:keys [result phase plan-state errors target]}
            (execute-action-plan
             service-state plan-state {} *admin-user* default-executor
             stop-execution-on-error action-plan)]
        (is (= {:ps 1} (dissoc plan-state :node-values)))
        (is (= (first service-state) target))
        (is (= :p phase))
        (is (not errors))
        (is (seq result))
        (is (:out (first result)))))
    (testing "group"
      (let [targets [(assoc g1 :target-type :group)]
            [r plan-state] ((action-plans service-state {} :g targets) {:ps 1})
            action-plan (first r)

            {:keys [result phase plan-state errors target]}
            (execute-action-plan
             service-state plan-state {} *admin-user* default-executor
             stop-execution-on-error action-plan)]
        (is (= {:ps 1} (dissoc plan-state :node-values)))
        (is (= (first targets) target))
        (is (= :group (:target-type target)))
        (is (= :g phase))
        (is (not errors))
        (is (seq result))))))
