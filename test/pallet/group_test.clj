(ns pallet.group-test
  (:require
   [clojure.core.async :refer [<!! chan]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.actions.test-actions :refer [fail]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute.node-list :as node-list]
   [pallet.core.node-os :refer [node-os]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.crate.os :refer [os]]
   [pallet.group :as group]
   [pallet.plan :refer :all]
   [pallet.session :as session :refer [executor plan-state recorder]]
   [pallet.test-utils :refer [make-localhost-compute]]))

;; (deftest service-state-test
;;   (testing "default groups"
;;     (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                    (make-node "n2" "g1" "192.168.1.2" :linux)]
;;           g1 (group-spec :g1)
;;           service (node-list-service [n1 n2])]
;;       (is (= [(assoc g1
;;                 :node (assoc n1 :service service)
;;                 :group-names #{:g1})
;;               (assoc g1
;;                 :node (assoc n2 :service service)
;;                 :group-names #{:g1})]
;;              (service-state service [g1])))))
;;   (testing "custom groups"
;;     (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                    (make-node "n2" "g1" "192.168.1.2" :linux)]
;;           g1 (group-spec
;;               :g1
;;               :node-filter #(= "192.168.1.2" (node/primary-ip %)))
;;           service (node-list-service [n1 n2])]
;;       (is (= [(assoc g1
;;                 :node (assoc n2 :service service)
;;                 :group-names #{:g1})]
;;              (service-state service [g1]))))))

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

;; (deftest group-deltas-test
;;   (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                  (make-node "n2" "g1" "192.168.1.2" :linux)]
;;         g1 (group-spec :g1 :count 1)
;;         service (node-list-service [n1 n2])]
;;     (is (= [[g1 {:actual 2 :target 1 :delta -1}]]
;;            (group-deltas (service-state service [g1]) [g1])))))

;; (deftest nodes-to-remove-test
;;   (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                  (make-node "n2" "g1" "192.168.1.2" :linux)]
;;         g1 (group-spec :g1 :count 1)
;;         service (node-list-service [n1 n2])
;;         service-state (service-state service [g1])]
;;     (is (= {g1 {:nodes [(assoc g1
;;                           :node (assoc n1 :service service)
;;                           :group-names #{:g1})]
;;                 :all false}}
;;            (nodes-to-remove
;;             service-state
;;             (group-deltas service-state [g1]))))))

;; (deftest execute-phase-on-target-test
;;   (let [n1 (make-localhost-node :group-name "g1")
;;         ga (fn test-plan-fn [] 1)
;;         g1 (group-spec :g1
;;              :phases {:p (plan-fn (exec-script "ls /"))
;;                       :g (plan-fn (ga))})
;;         service (node-list-service [n1])
;;         service-state (service-state service [g1])
;;         user (assoc *admin-user* :no-sudo true)]
;;     (testing "nodes"
;;       (let [{:keys [phase plan-state result target] :as r}
;;             (execute-phase-on-target
;;              service-state {:ps 1} {} :p
;;              (fn test-exec-setttings-fn [_ _]
;;                {:user user
;;                 :executor default-executor
;;                 :execute-status-fn stop-execution-on-error})
;;              (assoc g1 :node (:node (first service-state))))]
;;         (is (= {:ps 1} plan-state))
;;         (is (= (dissoc (first service-state) :group-names) target))
;;         (is (= :p phase))
;;         (is (seq result))
;;         (is (:out (first result)))
;;         (is (.contains (:out (first result)) "bin"))))
;;     (testing "group"
;;       (let [group-target (assoc g1 :target-type :group)
;;             {:keys [result phase plan-state target]}
;;             (execute-phase-on-target
;;              service-state {:ps 1} {} :g
;;              (fn test-exec-setttings-fn [_ _]
;;                {:user user
;;                 :executor default-executor
;;                 :execute-status-fn stop-execution-on-error})
;;              group-target)]
;;         (is (= {:ps 1} plan-state))
;;         (is (= group-target target))
;;         (is (= :group (:target-type target)))
;;         (is (= :g phase))
;;         (is (empty? result))))))        ; no actions can run on a group target

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

(deftest lift-op-test
  (let [session (session/create {:executor (ssh/ssh-executor)
                                 :plan-state (in-memory-plan-state)})
        host (localhost)]
    (testing "Successful, single phase lift-op"
      (let [g (group/group-spec :g
                :phases {:x (plan-fn [session] (exec-script* session "ls"))})
            t (assoc g :node host)
            result (group/lift-op session [:x] [t] {})]
        (is result "returns a result")
        (is (nil? (some #(some :error (:action-results %)) result))
            "has no action errors")))
    (testing "Unsuccessful, single phase lift-op"
      (let [g (group/group-spec :g
                :phases {:x (plan-fn [session] (fail session))})
            t (assoc g :node host)
            result (group/lift-op session [:x] [t] {})]
        (is result "returns a result")
        (is (some #(some :error (:action-results %)) result)
            "returns an action error")))
    (testing "Throws on exception if a plan-fn throws"
      (let [g (group/group-spec :g
                :phases {:x (plan-fn [session] (throw (ex-info "Test" {})))})
            t (assoc g :node host)]
        (is (thrown? Exception (group/lift-op session [:x] [t] {})))))))

(deftest service-state-test
  (testing "service state"
    (let [service (make-localhost-compute :group-name :local)
          ss (group/service-state service [(group/group-spec :local)])]
      (is (= 1 (count ss)))
      (is (= :local (:group-name (first ss))))
      (is (= (dissoc (localhost) :service)
             (dissoc (:node (first ss)) :service)))
      (is (every? :node ss)))))

(deftest all-group-nodes-test
  (testing "all-group-nodes"
    (let [service (make-localhost-compute :group-name :local)
          ss (group/all-group-nodes
              service
              [(group/group-spec :local)]
              [(group/group-spec :other)])]
      (is (= 1 (count ss)))
      (is (= :local (:group-name (first ss))))
      (is (= (dissoc (localhost) :service)
             (dissoc (:node (first ss)) :service))))))

(deftest node-count-adjuster-test
  (testing "node-count-adjuster"
    (let [session (session/create {:executor (ssh/ssh-executor)
                                   :plan-state (in-memory-plan-state)})
          service (make-localhost-compute :group-name :local)
          g (group/group-spec :g
              :count 1
              :phases {:x (plan-fn [session] (exec-script* session "ls"))})
          c (chan)
          targets (group/service-state service [(group/group-spec :local)])
          _ (is (every? :node targets))
          r (group/node-count-adjuster
             session
             service [g]
             targets
             c)
          [res e] (<!! c)]
      (is (map? res) "Result is a map")
      (is (= #{:new-nodes :old-nodes :results} (set (keys res)))
          "Result has the correct keys")
      (is (empty (:new-nodes res)) "Result has no new nodes")
      (is (empty (:old-nodes res)) "Result has no new nodes")
      (is (seq (:results res)) "Has phase results")
      (is (nil? e) "No exception thrown")
      (when e
        (print-cause-trace e)))))

(deftest converge*-test
  (testing "converge*"
    (let [session (session/create {:executor (ssh/ssh-executor)
                                   :plan-state (in-memory-plan-state)})
          service (make-localhost-compute :group-name :local)
          g (group/group-spec :g
              :count 1
              :phases {:x (plan-fn [session] (exec-script* session "ls"))})
          c (chan)
          targets (group/service-state service [(group/group-spec :local)])
          _ (is (every? :node targets))
          r (group/converge* [g] c {:compute service})
          [res e] (<!! c)]
      (is (map? res) "Result is a map")
      (is (= #{:new-nodes :old-nodes :results} (set (keys res)))
          "Result has the correct keys")
      (is (empty (:new-nodes res)) "Result has no new nodes")
      (is (empty (:old-nodes res)) "Result has no new nodes")
      (is (seq (:results res)) "Has phase results")
      (is (nil? e) "No exception thrown")
      (when e
        (print-cause-trace e)))))

(deftest converge-test
  (testing "converge"
    (let [service (make-localhost-compute :group-name :local)
          g (group/group-spec :g
              :count 1
              :phases {:x (plan-fn [session] (exec-script* session "ls"))})
          res (group/converge [g] :compute service)]
      (is (map? res) "Result is a map")
      (is (= #{:new-nodes :old-nodes :results} (set (keys res)))
          "Result has the correct keys")
      (is (empty (:new-nodes res)) "Result has no new nodes")
      (is (empty (:old-nodes res)) "Result has no new nodes")
      (is (seq (:results res)) "Has phase results"))))
