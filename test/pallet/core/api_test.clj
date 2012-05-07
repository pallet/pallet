(ns pallet.core.api-test
  (:require
   [pallet.node :as node])
  (:use
   clojure.test
   [pallet.action :only [clj-action]]
   [pallet.actions :only [exec-script]]
   [pallet.action-plan :only [stop-execution-on-error]]
   [pallet.compute.node-list
    :only [make-node make-localhost-node node-list-service]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.core.api-impl :only [with-script-for-node]]
   [pallet.executors :only [default-executor]]
   [pallet.utils :only [*admin-user*]]
   pallet.core.api))

(deftest service-state-test
  (testing "default groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service [n1 n2])]
      (is (= {:node->groups {n1 (list g1) n2 (list g1)}
              :group->nodes {g1 (list n1 n2)}}
             (service-state service [g1])))))
  (testing "custom groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec
              :g1
              :node-predicate #(= "192.168.1.2" (node/primary-ip %)))
          service (node-list-service [n1 n2])]
      (is (= {:node->groups {n1 [] n2 [g1]}
              :group->nodes {g1 [n2]}}
             (service-state service [g1]))))))

(deftest service-state-with-nodes-test
  (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
                    (make-node "n2" "g1" "192.168.1.2" :linux)
                    (make-node "n3" "g1" "192.168.1.3" :linux)]
        g1 (group-spec :g1)
        service (node-list-service [n1 n2])]
    (is (= {:node->groups {n1 [g1] n2 [g1]}
            :group->nodes {g1 [n1 n2]}}
           (service-state-with-nodes {} {g1 [n1 n2]})))
    (is (= {:node->groups {n1 [g1] n2 [g1] n3 [g1]}
            :group->nodes {g1 [n3 n1 n2]}}
           (service-state-with-nodes
             {:node->groups {n3 [g1]} :group->nodes {g1 [n3]}}
             {g1 [n1 n2]})))))

(deftest service-state-without-nodes-test
  (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
                    (make-node "n2" "g1" "192.168.1.2" :linux)
                    (make-node "n3" "g1" "192.168.1.3" :linux)]
        g1 (group-spec :g1)
        service (node-list-service [n1 n2 n3])
        ss (service-state service [g1])]
    (is (= {:node->groups {n1 [g1] n2 [g1]}
            :group->nodes {g1 [n1 n2]}}
           (service-state-without-nodes ss {g1 {:nodes [n3]}})))))

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
    (is (= {g1 {:nodes [n1] :all false}}
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
        service-state (service-state service [g1])]
    (testing "group-nodes"
      (let [[r plan-state] ((action-plans
                             service-state {} :p :group-nodes g1) {:ps 1})
            r1 (first r)]
        (is (seq r))
        (is (map? plan-state))
        (is (= {:ps 1} plan-state))
        (is (= 1 (count r)))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :p (:phase r1)))
        (is (= n1 (:target r1)))
        (is (= :node (:target-type r1)))))
    (testing "group-node-list"
      (let [[r plan-state] ((action-plans
                             service-state {} :p :group-node-list [g1 [n1]]) {})
            r1 (first r)]
        (is (seq r))
        (is (= 1 (count r)))
        (is (map? plan-state))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :p (:phase r1)))
        (is (= n1 (:target r1)))
        (is (= :node (:target-type r1)))))
    (testing "group"
      (let [[r plan-state] ((action-plans service-state {} :g :group g1) {})
            r1 (first r)]
        (is (seq r))
        (is (= 1 (count r)))
        (is (map? plan-state))
        (is (map? r1))
        (is (:action-plan r1))
        (is (= :g (:phase r1)))
        (is (= g1 (:target r1)))
        (is (= :group (:target-type r1)))))))

(deftest execute-action-plan-test
  (let [n1 (make-localhost-node :group "g1")
        ga (clj-action [session] [1 session])
        g1 (group-spec
            :g1
            :phases {:p (plan-fn (exec-script "ls"))
                     :g (plan-fn (ga))})
        service (node-list-service [n1])
        service-state (service-state service [g1])]
    (testing "group-nodes"
      (let [[r plan-state] ((action-plans
                             service-state {} :p :group-nodes g1) {:ps 1})
            action-plan (first r)

            {:keys [result phase plan-state errors target target-type]}
            (execute-action-plan
             service-state plan-state *admin-user* default-executor
             stop-execution-on-error action-plan)]
        (is (= {:ps 1} plan-state))
        (is (= n1 target))
        (is (= :node target-type))
        (is (= :p phase))
        (is (not errors))
        (is (seq result))
        (is (:out (first result)))))
    (testing "group"
      (let [[r plan-state] ((action-plans service-state {} :g :group g1)
                            {:ps 1})
            action-plan (first r)

            {:keys [result phase plan-state errors target target-type]}
            (execute-action-plan
             service-state plan-state *admin-user* default-executor
             stop-execution-on-error action-plan)]
        (is (= {:ps 1} plan-state))
        (is (= g1 target))
        (is (= :group target-type))
        (is (= :g phase))
        (is (not errors))
        (is (seq result))))))
