(ns pallet.target-ops-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.plan :refer [errors plan-fn]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.spec :refer [server-spec]]
   [pallet.target-ops :refer :all]
   [pallet.user :as user]
   [pallet.utils.async :refer [sync]]
   [schema.core :as schema :refer [validate]]))

(use-fixtures :once (logging-threshold-fixture))

(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(deftest lift-phase-test
  (testing "with a target with two phases"
    (let [spec (server-spec {:phases {:x (plan-fn [session]
                                           (exec-script* session "ls"))
                                      :y (plan-fn [session]
                                           (exec-script* session "ls")
                                           (exec-script* session "pwd"))}})
          node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
          target (assoc spec :node node)
          session (plan-session)]
      (testing "lifting one phase"
        (let [[result :as results] (sync (lift-phase session :x [target] nil))]
          (is (= 1 (count results)) "Runs a single phase on a single node")
          (is (= :x (:phase result)) "labels the phase in the result")
          (is (= target (:target result)) "labels the target in the result")
          (is (= 1 (count (:action-results result))) "Runs the plan action")
          (is (= ["ls"] (:args (:result (first (:action-results result)))))
              "invokes the correct phase")
          (is (not (errors results)))))
      (testing "with a second node"
        (testing "lifting the other phase"
          (let [node2 (assoc node :id "id2")
                target2 (assoc spec :node node2)
                results (sync (lift-phase session :y [target target2] nil))]
            (is (= 2 (count results)) "Runs a single phase on a both nodes")
            (is (every? #(= :y (:phase %)) results)
                "labels the phase in the results")
            (is (= #{target target2} (set (map :target results)))
                "labels the target in the results")
            (is (every? #(= 2 (count (:action-results %))) results)
                "Runs the plan action")
            (is (not (errors results))))))))
  (testing "with a target with a phase that throws"
    (let [e (ex-info "some error" {})
          spec (server-spec {:phases {:x (plan-fn [session]
                                           (exec-script* session "ls")
                                           (throw e))}})
          node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
          target (assoc spec :node node)
          session (plan-session)]
      (testing "lifting one phase"
        (is (thrown-with-msg? Exception #"lift-phase failed"
                              (sync (lift-phase session :x [target] nil))))
        (let [e (try
                  (sync (lift-phase session :x [target] nil))
                  (catch Exception e
                    e))
              data (ex-data e)
              [result :as results] (:results data)]
          (is (contains? data :results))
          (is (= 1 (count results)) "Runs a single phase on a single node")
          (is (= :x (:phase result)) "labels the phase in the result")
          (is (= target (:target result)) "labels the target in the result")
          (is (= 1 (count (:action-results result))) "Runs the plan action")
          (is (= ["ls"] (:args (:result (first (:action-results result)))))
              "invokes the correct phase")))))
  (testing "with a target with a phase that throws a domain error"
    (let [e (domain-info "some error" {})
          spec (server-spec {:phases {:x (plan-fn [session]
                                           (exec-script* session "ls")
                                           (throw e))}})
          node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
          target (assoc spec :node node)
          session (plan-session)]
      (testing "lifting one phase"
        (let [[result :as results] (sync (lift-phase session :x [target] nil))]
          (is (= 1 (count results)) "Runs a single phase on a single node")
          (is (= :x (:phase result)) "labels the phase in the result")
          (is (= target (:target result)) "labels the target in the result")
          (is (= 1 (count (:action-results result))) "Runs the plan action")
          (is (= ["ls"] (:args (:result (first (:action-results result)))))
              "invokes the correct phase")
          (is (errors results)))))))


(deftest lift-op-test
  (let [node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
        node2 (assoc node :id "id2")]

    (testing "with two targets with two phases"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (exec-script* session "pwd"))}})
            target (assoc spec :node node)
            spec2 (server-spec {:phases {:x (plan-fn [session]
                                              (exec-script* session "ls"))
                                         :y (plan-fn [session]
                                              (exec-script* session "ls")
                                              (exec-script* session "pwd"))}})
            target2 (assoc spec :node node2)
            session (plan-session)]
        (testing "lifting two phases"
          (let [results (sync (lift-op session [:x :y] [target target2] nil))]
            (is (= 4 (count results)) "Runs two plans on two nodes")
            (is (every? :phase results) "labels the target phases")
            (is (every? :target results) "labels the target in the result")
            (is (every? #(pos? (count (:action-results %))) results)
                "Runs the plan action")
            (is (not (errors results)) "Has no errors")))))

    (testing "with two targets with two phases with exceptions"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw
                                              (domain-info "some error" {}))
                                             (exec-script* session "pwd"))}})
            target (assoc spec :node node)
            spec2 (server-spec {:phases {:x (plan-fn [session]
                                              (exec-script* session "ls")
                                              (throw (ex-info "some error" {})))
                                         :y (plan-fn [session]
                                              (exec-script* session "ls")
                                              (exec-script* session "pwd"))}})

            target2 (assoc spec2 :node node2)

            session (plan-session)]
        (testing "lifting two phases, with non-domain exception"
          (is (thrown-with-msg?
               Exception #"lift-op failed"
               (sync (lift-op session [:x :y] [target target2] nil))))
          (let [e (try
                    (sync (lift-op session [:x :y] [target target2] nil))
                    (catch Exception e
                      e))
                {:keys [exceptions results]} (ex-data e)]
            (is (= 2 (count results)) "Runs one plan on two nodes")
            (is (every? #(= :x (:phase %)) results) "only runs the :x phase")
            (is (every? :target results) "labels the target in the result")
            (is (every? #(pos? (count (:action-results %))) results)
                "Runs the plan action")
            (is (errors results) "Has errors")))
        (testing "lifting two phases, with domain exception"
          (let [results (sync (lift-op session [:y :x] [target target2] nil))]
            (is (= 2 (count results)) "Runs one plan on two nodes")
            (is (every? #(= :y (:phase %)) results) "only runs the :y phase")
            (is (every? :target results) "labels the target in the result")
            (is (every? #(pos? (count (:action-results %))) results)
                "Runs the plan action")
            (is (errors results) "Has errors")))))))
