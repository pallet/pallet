(ns pallet.plan-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.actions.test-actions :as test-actions :refer [fail]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :as plan :refer [plan-executor]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.plan :refer :all]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
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

(defn node []
  {:id "somenode"
   :os-family :ubuntu
   :os-version "13.10"
   :packager :apt})

(def ubuntu-target {:node (node)})

(deftest execute-action-test
  (testing "execute-action"
    (let [session (-> (plan-session)
                      (set-target ubuntu-target))
          result (execute-action session {:action {:action-symbol 'a}
                                          :args [1]})]
      (is (validate action-result-map result))
      (is (= {:action 'a :args [1]}
             result)
          "returns the result of the action")
      (is (= [result] (plan/plan (executor session)))
          "uses the session executor")
      (is (= [result] (results (recorder session)))
          "records the results in the session recorder")))
  (testing "execute-action with fail action"
    (let [session (-> (plan-session)
                      (set-target ubuntu-target))
          e (try
              (execute-action session
                              {:action {:action-symbol `test-actions/fail}
                               :args []})
              (is false "should throw")
              (catch Exception e
                (is (ex-data e) "exception has ex-data")
                e))
          {:keys [result]} (ex-data e)]
      (is (validate action-result-map result))
      (is (= {:action `test-actions/fail
              :args []
              :error {:message "fail action"}}
             result)
          "returns the result of the action in the exception")
      (is (= [result] (plan/plan (executor session)))
          "records the failed action in the plan executor")
      (is (= [result] (results (recorder session)))
          "records the results in the session recorder"))))

(deftest execute-test
  (testing "execute"
    (let [session (plan-session)
          plan (fn [session]
                 (exec-script* session "ls")
                 :rv)
          result (execute session ubuntu-target plan)]
      (is (map? result))
      (is (= 1 (count (:action-results result))))
      (is (= [{:action 'pallet.actions.decl/exec-script*
               :args ["ls"],
               :options {:user (user session)}}]
             (:action-results result)))
      (is (= :rv (:return-value result)))
      (is (not (plan-errors result)))))
  (testing "execute with non-domain exception"
    (let [session (plan-session)
          e (ex-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Exception in plan-fn"
           (execute session ubuntu-target plan))
          "non domain error should throw")
      (testing "throws an exception"
        (let [execute-e (try (execute session ubuntu-target plan)
                             (catch clojure.lang.ExceptionInfo e
                               e))
              {:keys [action-results exception] :as result} (ex-data execute-e)]
          (is (contains? result :action-results))
          (is (= 1 (count action-results)))
          (is (= exception (root-cause execute-e)) "setting a cause exception")
          (is (= e exception) "reporting the cause exception")
          (is (validate plan-exception-map result)
              "reporting the action exception as cause")
          (is (:target result) "reporting the failed target")
          (is (not (contains? result :rv)) "doesn't record a return value")
          (is (plan-errors result))))))
  (testing "execute with domain exception"
    (let [session (plan-session)
          e (domain-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          result (execute session ubuntu-target plan)]
      (is (map? result) "doesn't throw")
      (is (= 1 (count (:action-results result))))
      (is (= e (:exception result)) "reports the exception")
      (is (not (contains? result :rv)) "doesn't record a return value")
      (is (plan-errors result)))))

(deftest plan-fn-test
  (is (plan-fn [session]))
  (is (thrown? Exception (eval `(plan-fn [])))
      "Plan-fn with zero args should fail to compile")
  (is (thrown? Exception (eval `(plan-fn nil)))
      "Plan-fn with no arg vector should fail to compile"))

(defmulti-plan mp (fn [session] (:dispatch session)))
(defmethod-plan mp :default [_] ::default)
(defmethod-plan mp :x [_] ::x)

(deftest multi-plan-test
  (is (= '[[session]] (-> #'mp meta :arglists)))
  (is (= ::default (mp {})))
  (is (= ::x (mp {:dispatch :x})))
  (is (thrown? clojure.lang.ArityException
               (mp {:dispatch :x} :y))))

;; TODO add plan-context tests



(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(def ubuntu-target {:override {:os-family :ubuntu
                               :os-version "13.10"
                               :packager :apt}
                    :node (node)})

(deftest execute-plan-fns-test
  (testing "execute-plan-fns"
    (let [session (plan-session)
          plan (fn [session]
                 (exec-script* session "ls")
                 :rv)
          target-plans [[ubuntu-target plan]]
          result (sync (execute-plan-fns session target-plans))]
      (is (= 1 (count result)))
      (is (every? #(validate target-result-map %) result))
      (is (= :rv (:return-value (first result))))
      (is (every? (complement plan-errors) result))
      (is (not (errors result)))
      (is (not (error-exceptions result)))
      (is (nil? (throw-errors result)))))
  (testing "execute with non-domain exception"
    (let [session (plan-session)
          e (ex-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [[ubuntu-target plan]]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"execute-plan-fns failed"
           (sync (execute-plan-fns session target-plans)))
          "non domain error should throw")
      (testing "throws an exception"
        (let [execute-e (try (sync (execute-plan-fns session target-plans))
                             (catch clojure.lang.ExceptionInfo e
                               e))
              {:keys [exceptions results] :as result} (ex-data execute-e)]
          (is (= e (root-cause execute-e)) "having the cause exception")
          (is (some #(= e (root-cause %)) exceptions)
              "listing an exception with the cause")
          (is (= 1 (count results)) "containing the results")
          (is (= ubuntu-target (:target (first results)))
              "reporting the target")
          (is (not (contains? result :rv)) "doesn't record a return value")
          (is (every? plan-errors results))
          (is (errors results))
          (is (error-exceptions results))
          (is (thrown? clojure.lang.ExceptionInfo (throw-errors results)))))))
  (testing "execute with domain exception"
    (let [session (plan-session)
          e (domain-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [[ubuntu-target plan]]
          result (sync (execute-plan-fns session target-plans))]
      (is (= 1 (count result)))
      (is (every? #(validate target-result-map %) result))
      (is (every? :exception result) "reports an exception")
      (is (every? #(not (contains? % :rv)) result)
          "Doesn't report a return value")
      (is (every? plan-errors result) "reports plan errors")
      (is (errors result))
      (is (error-exceptions result))
      (is (thrown? clojure.lang.ExceptionInfo (throw-errors result))))))
