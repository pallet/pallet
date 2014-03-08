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
