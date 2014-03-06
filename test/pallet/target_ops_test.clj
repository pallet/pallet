(ns pallet.target-ops-test
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
   [pallet.plan :as plan]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.target-ops :refer :all]
   [pallet.user :as user]
   [schema.core :as schema :refer [validate]]))

(use-fixtures :once (logging-threshold-fixture))

(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(def ubuntu-target {:override {:os-family :ubuntu
                               :os-version "13.10"
                               :packager :apt}
                    :node (localhost)})

(deftest execute-plan-fns-test
  (testing "execute-plan-fns"
    (let [session (plan-session)
          plan (fn [session]
                 (exec-script* session "ls")
                 :rv)
          target-plans [[ubuntu-target plan]]
          result (execute-plan-fns session target-plans)]
      (is (= 1 (count result)))
      (is (every? #(validate target-result-map %) result))
      (is (= :rv (:return-value (first result))))
      (is (every? (complement plan/plan-errors) result))))
  (testing "execute with non-domain exception"
    (let [session (plan-session)
          e (ex-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [[ubuntu-target plan]]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"execute-plan-fns failed"
           (execute-plan-fns session target-plans))
          "non domain error should throw")
      (testing "throws an exception"
        (let [execute-e (try (execute-plan-fns session target-plans)
                             (catch clojure.lang.ExceptionInfo e
                               e))
              {:keys [exception results] :as result} (ex-data execute-e)]
          (is (= e (root-cause execute-e)) "containing the cause exception")
          (is (= 1 (count results)) "containing the results")
          (is (= ubuntu-target (:target (first results)))
              "reporting the target")
          (is (not (contains? result :rv)) "doesn't record a return value")
          (is (every? plan/plan-errors results))))))
  (testing "execute with domain exception"
    (let [session (plan-session)
          e (domain-info "some exception" {})
          plan (fn [session]
                 (exec-script* session "ls")
                 (throw e))
          target-plans [[ubuntu-target plan]]
          result (execute-plan-fns session target-plans)]
      (is (= 1 (count result)))
      (is (every? #(validate target-result-map %) result))
      (is (every? :exception result) "reports an exception")
      (is (every? #(not (contains? % :rv)) result)
          "Doesn't report a return value")
      (is (every? plan/plan-errors result) "reports plan errors"))))
