(ns pallet.target-ops-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :refer [>! >!! <!! chan go put!]]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.actions :refer [exec-script*]]
   [pallet.compute.protocols :as impl]
   [pallet.compute.test-provider :refer [test-service]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.node :as node]
   [pallet.plan :refer [errors plan-fn]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.spec :refer [server-spec]]
   [pallet.target-ops :refer :all]
   [pallet.user :as user]
   [pallet.utils.async :refer [go-try sync]]
   [schema.core :as schema :refer [validate]]
   [taoensso.timbre :refer [debugf]]))

(use-fixtures :once (logging-threshold-fixture))

(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(deftest execute-target-phase-test
  (let [phase-x {:phase :x}
        phase-y {:phase :y}]
    (testing "with a target with two phases"
      (let [spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls"))
                                        :y (plan-fn [session]
                                             (exec-script* session "ls")
                                             (exec-script* session "pwd"))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            ;; target {:target node :phases (:phases spec)}
            session (plan-session)]
        (testing "lifting one phase"
          (let [target-plan (target-plan {:target node :spec spec} phase-x)
                {[result :as results] :results}
                (sync (execute-target-phase session {:result-id phase-x
                                           :target-plans [target-plan]}))]
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the results")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")
            (is (not (errors results)))))
        (testing "with a second node"
          (testing "lifting the other phase"
            (let [node2 (assoc node :id "id2")
                  target2 (assoc spec :node node2)
                  target-plans [(target-plan
                                 {:target node :spec spec} phase-y)
                                (target-plan
                                 {:target node2 :spec spec} phase-y)]
                  {:keys [results]} (sync
                                     (execute-target-phase session
                                                 {:result-id phase-y
                                                  :target-plans target-plans}))]
              (is (= 2 (count results)) "Runs a single phase on a both nodes")
              (is (every? #(= :y (:phase %)) results)
                  "labels the phase in the results")
              (is (= #{node node2} (set (map :target results)))
                  "labels the target in the results")
              (is (every? #(= 2 (count (:action-results %))) results)
                  "Runs the plan action")
              (is (not (errors results))))))))
    (testing "with a target with a phase that throws"
      (let [e (ex-info "some error" {})
            spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw e))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            session (plan-session)
            target-plan (target-plan {:target node :spec spec} phase-x)]
        (testing "lifting one phase"
          (is (thrown-with-msg?
               Exception #"execute-target-phase failed"
               (sync
                (execute-target-phase session {:result-id {:phase :x}
                                     :target-plans [target-plan]}))))
          (let [e (try
                    (sync (execute-target-phase session {:result-id {:phase :x}
                                               :target-plans [target-plan]}))
                    (catch Exception e
                      e))
                data (ex-data e)
                [result :as results] (:results data)]
            (is (contains? data :results))
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the result")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")))))
    (testing "with a target with a phase that throws a domain error"
      (let [e (domain-info "some error" {})
            spec (server-spec {:phases {:x (plan-fn [session]
                                             (exec-script* session "ls")
                                             (throw e))}})
            node {:id "id" :os-family :ubuntu :os-version "13.10"
                  :packager :apt}
            session (plan-session)
            target-plan (target-plan {:target node :spec spec} phase-x)]
        (testing "lifting one phase"
          (let [{[result :as results] :results}
                (sync (execute-target-phase session {:result-id phase-x
                                           :target-plans [target-plan]}))]
            (is (= 1 (count results)) "Runs a single phase on a single node")
            (is (= :x (:phase result)) "labels the phase in the result")
            (is (= node (:target result)) "labels the target in the result")
            (is (= 1 (count (:action-results result))) "Runs the plan action")
            (is (= ["ls"] (:args (first (:action-results result))))
                "invokes the correct phase")
            (is (errors results))))))))

(deftest parallel-phases-test
  (let [c (chan)]
    (testing "one result without exceptions"
      (let [fs [(fn [_ c] (go (>! c {:results [true]})))]]
        (parallel-phases fs nil c)
        (let [r (<!! c)]
          (is (= {:results [true]} r))
          (is (nil? (when-let [e (:exception r)] (throw e)))))))
    (testing "one result with exceptions"
      (let [e (ex-info "some exception" {})
            fs [(fn [_ c] (go (>! c {:results [true] :exception e})))]]
        (parallel-phases fs nil c)
        (let [{:keys [results exception]} (<!! c)]
          (is (= [true] results))
          (is (= e (root-cause exception))))))
    (testing "two result without exceptions"
      (let [fs [(fn [_ c] (go (>! c {:results [:ok1]})))
                (fn [_ c] (go (>! c {:results [:ok2]})))]]
        (parallel-phases fs nil c)
        (let [r (<!! c)]
          (is (= {:results [:ok1 :ok2]} r))
          (is (nil? (when-let [e (:exception r)] (throw e)))))))
    (testing "two result with exception"
      (let [e (ex-info "some exception" {})
            fs [(fn [_ c] (go (>! c {:results [:ok1] :exception e})))
                (fn [_ c] (go (>! c {:results [:ok2]})))]]
        (parallel-phases fs nil c)
        (let [{:keys [results exception]} (<!! c)]
          (is (= [:ok1 :ok2] results))
          (is (= e (root-cause exception))))))))

;; (deftest sync-phases-test
;;   (testing "single-step"
;;     (testing "with no modifiers"
;;       (let [step {:op (fn [_ c] (go (>! c {:results [true]})))}
;;             c (chan)]
;;         (sync-phases [step] nil c)
;;         (let [r (<!! c)]
;;           (is (= {:results [true]} r))
;;           (when-let [e (:exception r)]
;;             (clojure.stacktrace/print-cause-trace e)))))
;;     (testing "with no modifiers and an initial state"
;;       (let [step {:op (fn [state c] (go (>! c {:results [state]})))}
;;             c (chan)]
;;         (sync-phases [step] {:state {:init-state 1}} c)
;;         (let [r (<!! c)]
;;           (is (= {:state {:init-state 1}
;;                   :results [{:init-state 1}]}
;;                  r))))))
;;   (testing "two-steps"
;;     (testing "with no modifiers"
;;       (let [steps [{:op (fn [_ c] (go (>! c {:results [{:ok1 true}]})))}
;;                    {:op (fn [_ c] (go (>! c {:results [{:ok2 true}]})))}]
;;             c (chan)]
;;         (sync-phases steps nil c)
;;         (let [r (<!! c)]
;;           (is (= {:results [{:ok1 true} {:ok2 true}]} r)))))
;;     (testing "with :state-update"
;;       (let [steps [{:op (fn [_ c] (go (>! c {:results [{:ok1 true}]})))
;;                     :state-update (fn [state result]
;;                                     (update-in state [:i] inc))}
;;                    {:op (fn [state c] (go (>! c {:results [state]})))}]
;;             c (chan)]
;;         (sync-phases steps {:state {:i 0}} c)
;;         (let [r (<!! c)]
;;           (is (= {:results [{:ok1 true} {:i 1}] :state {:i 1}} r)))))
;;     (testing "with :flow aborter"
;;       (let [steps [{:op (fn [_ c] (go (>! c {:results [{:ok1 true}]})))
;;                     :flow (fn [_ _ _])}
;;                    {:op (fn [_ c] (go (>! c {:results [{:not-ok2 false}]})))}]
;;             c (chan)]
;;         (sync-phases steps nil c)
;;         (let [r (<!! c)]
;;           (is (= {:results [{:ok1 true}]} r)))))
;;     (testing "with :flow step addition"
;;       (let [steps [{:op (fn [_ c] (go (>! c {:results [{:ok1 true}]})))
;;                     :flow (fn [_ _ _]
;;                             [{:op (fn [_ c]
;;                                     (go (>! c {:results [{:ok2 true}]})))}])}]
;;             c (chan)]
;;         (sync-phases steps nil c)
;;         (let [r (<!! c)]
;;           (is (= {:results [{:ok1 true}{:ok2 true}]} r)))))))

;; (deftest sync-parallel-test
;;   (testing "one f"
;;     (let [fs [(fn [c] (go (>! c {:results [{:ok1 true}]})))]
;;           c (chan)]
;;       (sync-parallel fs c)
;;       (let [r (<!! c)]
;;         (is (= {:results [{:ok1 true}]} r)))))
;;   (testing "two fs"
;;     (let [fs [(fn [c] (go (>! c {:results [{:ok1 true}]})))
;;               (fn [c] (go (>! c {:results [{:ok2 true}]})))]
;;           c (chan)]
;;       (sync-parallel fs c)
;;       (let [r (<!! c)]
;;         (is (= {:results [{:ok1 true}{:ok2 true}]} r))))))

;; (deftest lift-abort-on-error-test
;;   (let [node {:id "id" :os-family :ubuntu :os-version "13.10" :packager :apt}
;;         node2 (assoc node :id "id2")]

;;     (testing "with two targets with two phases"
;;       (let [spec (server-spec {:phases {:x (plan-fn [session]
;;                                              (exec-script* session "ls"))
;;                                         :y (plan-fn [session]
;;                                              (exec-script* session "ls")
;;                                              (exec-script* session "pwd"))}})
;;             target-spec {:spec spec :target node}
;;             spec2 (server-spec {:phases {:x (plan-fn [session]
;;                                               (exec-script* session "ls"))
;;                                          :y (plan-fn [session]
;;                                               (exec-script* session "ls")
;;                                               (exec-script* session "pwd"))}})
;;             target-spec2 {:spec spec :target node2}
;;             target-specs [target-spec target-spec2]
;;             session (plan-session)]
;;         (testing "lifting two phases"
;;           (let [target-phases (mapv #(target-phase target-specs %)
;;                                     [{:phase :x} {:phase :y}])
;;                 {:keys [results]} (sync
;;                                    (lift-abort-on-error
;;                                     session target-phases nil))]
;;             (is (= 4 (count results)) "Runs two plans on two nodes")
;;             (is (every? :phase results) "labels the target phases")
;;             (is (every? :target results) "labels the target in the result")
;;             (is (every? #(pos? (count (:action-results %))) results)
;;                 "Runs the plan action")
;;             (is (not (errors results)) "Has no errors")))))

;;     (testing "with two targets with two phases with exceptions"
;;       (let [spec (server-spec {:phases {:x (plan-fn [session]
;;                                              (exec-script* session "ls"))
;;                                         :y (plan-fn [session]
;;                                              (exec-script* session "ls")
;;                                              (throw
;;                                               (domain-info "some error" {}))
;;                                              (exec-script* session "pwd"))}})
;;             target-spec {:spec spec :target node}
;;             spec2 (server-spec {:phases {:x (plan-fn [session]
;;                                               (exec-script* session "ls")
;;                                               (throw (ex-info "some error" {})))
;;                                          :y (plan-fn [session]
;;                                               (exec-script* session "ls")
;;                                               (exec-script* session "pwd"))}})

;;             target-spec2 {:spec spec2 :target node2}
;;             target-specs [target-spec target-spec2]
;;             session (plan-session)]
;;         (testing "lifting two phases, with non-domain exception"
;;           (let [target-phases (mapv #(target-phase target-specs %)
;;                                     [{:phase :x} {:phase :y}])]
;;             (is (thrown-with-msg?
;;                  Exception #"execute-target-phase failed"
;;                  (sync (lift-abort-on-error session target-phases nil))))
;;             (let [e (try
;;                       (sync (lift-abort-on-error session target-phases nil))
;;                       (catch Exception e
;;                         e))
;;                   {:keys [exceptions results]} (ex-data e)]
;;               (is (= 2 (count results)) "Runs one plan on two nodes")
;;               (is (every? #(= :x (:phase %)) results) "only runs the :x phase")
;;               (is (every? :target results) "labels the target in the result")
;;               (is (every? #(pos? (count (:action-results %))) results)
;;                   "Runs the plan action")
;;               (is (errors results) "Has errors"))))
;;         (testing "lifting two phases, with domain exception"
;;           (let [target-phases (mapv #(target-phase target-specs %)
;;                                     [{:phase :y} {:phase :x}])
;;                 {:keys [results]} (sync
;;                                    (lift-abort-on-error
;;                                     session target-phases nil))]
;;             (is (= 2 (count results)) "Runs one plan on two nodes")
;;             (is (every? #(= :y (:phase %)) results) "only runs the :y phase")
;;             (is (every? :target results) "labels the target in the result")
;;             (is (every? #(pos? (count (:action-results %))) results)
;;                 "Runs the plan action")
;;             (is (errors results) "Has errors")))))))

;; (deftest create-targets-test
;;   (testing "Create targets with explicit phase, no plan-state."
;;     (let [session (plan-session)
;;           result (sync (create-targets
;;                          session
;;                          (test-service {})
;;                          {:image {:image-id "x" :os-family :ubuntu}}
;;                          (session/user session)
;;                          3
;;                          "base"
;;                          (plan-fn [session]
;;                            (debugf "settings for new node"))
;;                          (plan-fn [session]
;;                            (debugf "bootstrap for new node")
;;                            {:os-family :ubuntu})))
;;           {:keys [results new-targets old-targets state]} result]
;;       (is (= 6 (count results))
;;           "creates the correct number of targets and phases")
;;       (is (= 3 (count new-targets))
;;           "creates the correct number of targets")
;;       (is (empty? old-targets)
;;           "removes no targets")
;;       (is (not (errors (:results results)))
;;           "doesn't report any errors"))))
