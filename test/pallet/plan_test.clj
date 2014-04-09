(ns pallet.plan-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :refer [>!]]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.string :refer [split-lines]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions.test-actions :as test-actions :refer [fail]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.core.context :refer [with-context]]
   [pallet.core.executor.plan :as plan :refer [plan-executor]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-error? domain-info]]
   [pallet.middleware :refer [execute-on-filtered]]
   [pallet.plan :refer :all]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.user :as user]
   [pallet.utils.async :refer [go-try sync]]
   [schema.core :as schema :refer [validate]]
   [taoensso.timbre :refer [warnf]]))

(defn test-name-in-context
  [f]
  (with-context {:test (mapv (comp :name meta) *testing-vars*)}
    (f)))

(use-fixtures :once (logging-threshold-fixture :warn))
(use-fixtures :each test-name-in-context)

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

(def ubuntu-node (node))

(deftest execute-action-test
  (testing "execute-action"
    (let [session (-> (plan-session)
                      (set-target ubuntu-node))
          result (execute-action session {:action {:action-symbol 'a
                                                   :impls (atom nil)}
                                          :args [1]})]
      (is (validate ActionResult result))
      (is (= {:action 'a :args [1]}
             result)
          "returns the result of the action")
      (is (= [result] (plan/plan (executor session)))
          "uses the session executor")
      (is (= [result] (results (recorder session)))
          "records the results in the session recorder")))
  (testing "execute-action with fail action"
    (let [session (-> (plan-session)
                      (set-target ubuntu-node))
          e (try
              (execute-action session
                              {:action {:action-symbol `test-actions/fail
                                        :impls (atom nil)}
                               :args []})
              (is false "should throw")
              (catch Exception e
                (is (ex-data e) "exception has ex-data")
                e))
          {:keys [result]} (ex-data e)]
      (is (validate ActionResult result))
      (is (= {:action `test-actions/fail
              :args []
              :error {:message "fail action"}}
             result)
          "returns the result of the action in the exception")
      (is (= [result] (plan/plan (executor session)))
          "records the failed action in the plan executor")
      (is (= [result] (results (recorder session)))
          "records the results in the session recorder"))))

(deftest execute-plan*-test
  (testing "execute-plan*"
    (let [session (plan-session)
          plan (fn [session]
                 (exec-script* session "ls")
                 :rv)
          result (execute-plan* session ubuntu-node plan)]
      (is (map? result))
      (is (= 1 (count (:action-results result))))
      (is (= [{:action 'pallet.actions.decl/exec-script*
               :args ["ls"],
               :options {:user (user session)}}]
             (:action-results result)))
      (is (= :rv (:return-value result)))
      (is (not (plan-errors result)))))
  (testing "execute-plan* with record-all false"
    (let [session (plan-session)
          plan (fn [session]
                 (with-action-options session {:record-all false}
                   (exec-script* session "ls"))
                 :rv)
          result (execute-plan* session ubuntu-node plan)]
      (is (map? result))
      (is (= 1 (count (:action-results result))))
      (is (= [{}] (:action-results result)) "no details in action-results")
      (is (= :rv (:return-value result)))
      (is (not (plan-errors result)))))
  (testing "execute-plan* with fail action"
    (let [session (plan-session)
          plan (fn [session]
                 (fail session)
                 :rv)
          result (execute-plan* session ubuntu-node plan)]
      (is (map? result))
      (is (= 1 (count (:action-results result))))
      (is (= [{:action 'pallet.actions.test-actions/fail
               :args []
               :options {:user (user session)}
               :error {:message "fail action"}}]
             (:action-results result)))
      (is (not (contains? result :return-value)))
      (is (plan-errors result))))
  (testing "execute-plan* with non-domain exception"
    (let [session (plan-session)
          e (ex-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"Exception in plan-fn"
           (execute-plan* session ubuntu-node plan))
          "non domain error should throw")
      (testing "throws an exception"
        (let [execute-e (try (execute-plan* session ubuntu-node plan)
                             (catch clojure.lang.ExceptionInfo e
                               e))
              {:keys [action-results exception] :as result} (ex-data execute-e)]
          (is (contains? result :action-results))
          (is (= 1 (count action-results)))
          (is (= exception (root-cause execute-e)) "setting a cause exception")
          (is (= e exception) "reporting the cause exception")
          (is (validate PlanTargetResult result)
              "reporting the action exception as cause")
          (is (:target result) "reporting the failed target")
          (is (not (contains? result :rv)) "doesn't record a return value")
          (is (plan-errors result))))))
  (testing "execute-plan* with domain exception"
    (let [session (plan-session)
          e (domain-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          result (execute-plan* session ubuntu-node plan)]
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


(deftest execute-plans-test
  (testing "execute-plans"
    (let [session (plan-session)
          plan (fn [session]
                 (exec-script* session "ls")
                 :rv)
          target-plans [{:target ubuntu-node :plan-fn plan}]
          {:keys [results]} (sync (execute-plans session target-plans))]
      (is (= 1 (count results)))
      (is (or (validate [TargetResult] results) true))
      (is (= :rv (:return-value (first results))))
      (is (every? (complement plan-errors) results))
      (is (not (errors results)))
      (is (not (error-exceptions results)))
      (is (nil? (throw-errors results)))))
  (testing "execute-plans with plan middleware"
    (testing "to filter out a plan"
      (let [session (plan-session)
            plan
            ^{:middleware (-> execute-plan*
                              (execute-on-filtered (constantly false)))}
            (fn
              [session]
              (exec-script* session "ls")
              :rv)
            target-plans [{:target ubuntu-node :plan-fn plan}]
            {:keys [results] :as r} (sync (execute-plans session target-plans))]
        (is (not (seq results)))
        (is (not (errors results)))
        (is (not (error-exceptions results)))
        (is (nil? (throw-errors results)))))
    (testing "to include a filtered plan"
      (let [session (plan-session)
            plan ^{:middleware (-> execute-plan*
                                   (execute-on-filtered (constantly true)))}
            (fn [session]
              (exec-script* session "ls")
              :rv)
            target-plans [{:target ubuntu-node :plan-fn plan}]
            {:keys [results]} (sync (execute-plans session target-plans))]
        (is (= 1 (count results)))
        (is (or (validate [TargetResult] results) true))
        (is (= :rv (:return-value (first results))))
        (is (every? (complement plan-errors) results))
        (is (not (errors results)))
        (is (not (error-exceptions results)))
        (is (nil? (throw-errors results))))))
  (testing "execute-plans with phase middleware"
    (let [mw (fn [handler flag]
               (fn [session target-plans ch]
                 (if flag
                   (handler session target-plans ch)
                   (go-try ch
                     (>! ch {})))))]
      (testing "to filter out plans"
        (let [session (plan-session)
              plan
              ^{:phase-middleware (-> execute-plans* (mw false))}
              (fn
                [session]
                (exec-script* session "ls")
                :rv)
              target-plans [{:target ubuntu-node :plan-fn plan}]
              {:keys [results]} (sync (execute-plans session target-plans))]
          (is (not (seq results)))
          (is (not (errors results)))
          (is (not (error-exceptions results)))
          (is (nil? (throw-errors results)))))
      (testing "to include filtered plans"
        (let [session (plan-session)
              plan ^{:phase-middleware (-> execute-plans* (mw true))}
              (fn
                [session]
                (exec-script* session "ls")
                :rv)
              target-plans [{:target ubuntu-node :plan-fn plan}]
              {:keys [results]} (sync (execute-plans session target-plans))]
          (is (= 1 (count results)))
          (is (every? #(validate TargetResult %) results))
          (is (= :rv (:return-value (first results))))
          (is (every? (complement plan-errors) results))
          (is (not (errors results)))
          (is (not (error-exceptions results)))
          (is (nil? (throw-errors results)))))))
  (testing "execute-plans with non-domain exception"
    (let [session (plan-session)
          e (ex-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [{:target ubuntu-node :plan-fn plan}]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"execute-plans failed"
           (sync (execute-plans session target-plans)))
          "non domain error should throw")
      (testing "throws an exception"
        (let [execute-e (try (sync (execute-plans session target-plans))
                             (catch clojure.lang.ExceptionInfo e
                               e))
              {:keys [exceptions results] :as result} (ex-data execute-e)]
          (is (instance? Throwable execute-e))
          (is (= e (root-cause execute-e)) "having the cause exception")
          (is (some #(= e (root-cause %)) exceptions)
              "listing an exception with the cause")
          (is (= 1 (count results)) "containing the results")
          (is (= ubuntu-node (:target (first results)))
              "reporting the target")
          (is (not (contains? result :rv)) "doesn't record a return value")
          (is (every? plan-errors results))
          (is (errors results))
          (is (error-exceptions results))
          (is (thrown? clojure.lang.ExceptionInfo (throw-errors results)))))))
  (testing "execute-plans with domain exception"
    (let [session (plan-session)
          e (domain-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [{:target ubuntu-node :plan-fn plan}]
          {:keys [results]} (sync (execute-plans session target-plans))]
      (is (= 1 (count results)))
      (is (every? #(validate TargetResult %) results))
      (is (every? :exception results) "reports an exception")
      (is (every? #(not (contains? % :rv)) results)
          "Doesn't report a return value")
      (is (every? plan-errors results) "reports plan errors")
      (is (errors results))
      (is (error-exceptions results))
      (is (thrown? clojure.lang.ExceptionInfo (throw-errors results))))))


(deftest plan-fn-test
  (let [f (plan-fn fname ([s x] (warnf "%s" x)) ([s] (fname s :x)))
        g (plan-fn [s x] (warnf "%s" x))
        session (-> (plan-session)
                    (set-target ubuntu-node))]
    (is (.contains (with-out-str (f session :x)) "[fname]"))
    (is (.contains (with-out-str (f session)) "[fname]"))
    (is (= 1 (count (split-lines (with-out-str (f session)))))
        "logged once")
    (is (.contains (with-out-str (g session :x)) "[]"))
    (is (thrown-with-msg?
         Exception #"Value does not match schema"
         (f {} :x))
        "Complains about invalid target session")
    (is (thrown-with-msg?
         Exception #"Parameter declaration missing"
         (eval `(plan-fn)))
        "Complains about defplan with no arg vector")
    (is (thrown-with-msg?
         Exception #"Parameter declaration missing"
         (eval `(plan-fn ~'x)))
        "Complains about defplan with name and no arg vector")
    (is (thrown-with-msg?
         Exception  #"defplan requires at least a session argument"
         (eval `(plan-fn ~'x [])))
        "Complains about defplan with empty arg vector")))

(defplan f
  ([s] (f s :x))
  ([s x] (warnf "%s" x)))

(deftest defplan-test
  (let [session (-> (plan-session)
                    (set-target ubuntu-node))]
    (is (.contains (with-out-str (f session)) ":x"))
    (is (= 1 (count (split-lines (with-out-str (f session)))))
        "logged once")
    (is (.contains (with-out-str (f session :x)) ":x"))
    (is (= 1 (count (split-lines (with-out-str (f session :x)))))
        "logged once"))
  (is (thrown-with-msg?
       Exception #"Value does not match schema"
       (f {}))
      "Complains about invalid target session")
  (is (thrown-with-msg?
       Exception #"Parameter declaration missing"
       (eval `(defplan ~'x)))
      "Complains about defplan with no arg vector")
  (is (thrown-with-msg?
       Exception  #"defplan requires at least a session argument"
       (eval `(defplan ~'x [])))
      "Complains about defplan with empty arg vector"))
