(ns pallet.core.api-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute.node-list
    :refer [make-localhost-node make-node node-list-service]]
   [pallet.core.api
    :refer [execute-phase-on-target
            group-deltas
            nodes-to-remove
            service-state
            stop-execution-on-error]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.executors :refer [default-executor]]
   [pallet.node :as node]))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-state-test
  (testing "default groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec :g1)
          service (node-list-service [n1 n2])]
      (is (= [(assoc g1
                :node (assoc n1 :service service)
                :group-names #{:g1})
              (assoc g1
                :node (assoc n2 :service service)
                :group-names #{:g1})]
             (service-state service [g1])))))
  (testing "custom groups"
    (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                   (make-node "n2" "g1" "192.168.1.2" :linux)]
          g1 (group-spec
              :g1
              :node-filter #(= "192.168.1.2" (node/primary-ip %)))
          service (node-list-service [n1 n2])]
      (is (= [(assoc g1
                :node (assoc n2 :service service)
                :group-names #{:g1})]
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
    (is (= [[g1 {:actual 2 :target 1 :delta -1}]]
           (group-deltas (service-state service [g1]) [g1])))))

(deftest nodes-to-remove-test
  (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
                 (make-node "n2" "g1" "192.168.1.2" :linux)]
        g1 (group-spec :g1 :count 1)
        service (node-list-service [n1 n2])
        service-state (service-state service [g1])]
    (is (= {g1 {:nodes [(assoc g1
                          :node (assoc n1 :service service)
                          :group-names #{:g1})]
                :all false}}
           (nodes-to-remove
            service-state
            (group-deltas service-state [g1]))))))

(deftest action-plan-test
  ;; (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
  ;;       g1 (group-spec :g1)
  ;;       service (node-list-service [n1])
  ;;       service-state (service-state service [g1])
  ;;       [r plan-state] (with-script-for-node {:node n1} nil
  ;;                        ((action-plan
  ;;                          service-state {}
  ;;                          (plan-fn (exec-script "ls"))
  ;;                          nil
  ;;                          {:server {:node n1}})
  ;;                         {:ps 1}))]
  ;;   (is (seq r))
  ;;   (is (map? plan-state))
  ;;   (is (= {:ps 1} plan-state)))
  ;; (testing "with phase arguments"
  ;;   (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
  ;;       g1 (group-spec :g1)
  ;;       service (node-list-service [n1])
  ;;       service-state (service-state service [g1])
  ;;         [r plan-state] (with-script-for-node {:node n1} nil
  ;;                        ((action-plan
  ;;                          service-state {}
  ;;                          (fn [x] (exec-script "ls" ~x))
  ;;                          ["/bin"]
  ;;                          {:server {:node n1}})
  ;;                         {:ps 1}))]
  ;;   (is (seq r))
  ;;   (is (map? plan-state))
  ;;   (is (= {:ps 1} plan-state))))
  )

;; (deftest action-plans-test
;;   (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
;;         g1 (group-spec
;;             :g1
;;             :phases {:p (plan-fn (exec-script "ls"))
;;                      :g (plan-fn 1)})
;;         service (node-list-service [n1])
;;         n1 (assoc n1 :service service)
;;         service-state (service-state service [g1])]
;;     (testing "nodes"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :p service-state) {:ps 1})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (map? plan-state))
;;         (is (= {:ps 1} plan-state))
;;         (is (= 1 (count r)))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :p (:phase r1)))
;;         (is (= (assoc g1 :node n1 :group-names #{:g1}) (:target r1)))))
;;     (testing "nodes"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :p service-state) {})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (= 1 (count r)))
;;         (is (map? plan-state))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :p (:phase r1)))
;;         (is (= (assoc g1 :node n1 :group-names #{:g1}) (:target r1)))))
;;     (testing "group"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :g
;;                              [(assoc g1 :target-type :group)])
;;                             {})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (= 1 (count r)))
;;         (is (map? plan-state))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :g (:phase r1)))
;;         (is (= (assoc g1 :target-type :group) (:target r1)))
;;         (is (= :group (-> r1 :target :target-type)))))))

(deftest execute-phase-on-target-test
  (let [n1 (make-localhost-node :group-name "g1")
        ga (fn test-plan-fn [] 1)
        g1 (group-spec :g1
             :phases {:p (plan-fn (exec-script "ls /"))
                      :g (plan-fn (ga))})
        service (node-list-service [n1])
        service-state (service-state service [g1])
        user (assoc *admin-user* :no-sudo true)]
    (testing "nodes"
      (let [{:keys [phase plan-state result target] :as r}
            (execute-phase-on-target
             service-state {:ps 1} {} :p
             (fn test-exec-setttings-fn [_ _]
               {:user user
                :executor default-executor
                :execute-status-fn stop-execution-on-error})
             (assoc g1 :node (:node (first service-state))))]
        (is (= {:ps 1} plan-state))
        (is (= (dissoc (first service-state) :group-names) target))
        (is (= :p phase))
        (is (seq result))
        (is (:out (first result)))
        (is (.contains (:out (first result)) "bin"))))
    (testing "group"
      (let [group-target (assoc g1 :target-type :group)
            {:keys [result phase plan-state target]}
            (execute-phase-on-target
             service-state {:ps 1} {} :g
             (fn test-exec-setttings-fn [_ _]
               {:user user
                :executor default-executor
                :execute-status-fn stop-execution-on-error})
             group-target)]
        (is (= {:ps 1} plan-state))
        (is (= group-target target))
        (is (= :group (:target-type target)))
        (is (= :g phase))
        (is (empty? result))))))        ; no actions can run on a group target
