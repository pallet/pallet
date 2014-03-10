(ns pallet.action-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :as plan]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.session :as session]
   [pallet.user :as user]))

(use-fixtures :once (logging-threshold-fixture))

(defn test-session
  []
  (-> (session/create {:executor (plan/plan-executor)
                       :recorder (in-memory-recorder)})
      (session/set-target {:node {:id "localhost"}})
      (session/set-user user/*admin-user*)))

(deftest declare-action-test
  (testing "A declared action"
    (let [a (declare-action 'a {})]
      (testing "has metadata"
        (is (map? (:action (meta a))) "Action function has :action metadata"))
      (testing "execution"
        (let [session (test-session)]
          (a session :a :b)
          (is (= [{:args [:a :b]
                   :action 'a
                   :options {:user user/*admin-user*}}]
                 (plan/plan (session/executor session)))))))))

(defaction b "b doc" {:m 1} [session x])

(deftest defaction-test
  (testing "action metadata"
    (is (= "b doc" (:doc (meta #'b))) "Action var has doc")
    (is (= 1 (:m (meta #'b))) "Action var has metadata")
    (is (map? (:action (meta b))) "Action function has :action metadata")
    (is (= {:m 1} (:options (:action (meta b)))) "Action has action :options"))
  (testing "action execution"
    (let [session (test-session)]
      (b session :a)
      (is (= [{:args [:a]
               :action 'pallet.action-test/b
               :options {:user user/*admin-user*
                         :m 1}}]
             (plan/plan (session/executor session))))))
  (testing "action execution with action options"
    (let [session (test-session)]
      (with-action-options session {:sudo-user "user"}
        (b session :a))
      (is (= [{:args [:a]
               :action 'pallet.action-test/b
               :options {:sudo-user "user"
                         :m 1
                         :user user/*admin-user*}}]
             (plan/plan (session/executor session)))))))

(deftest effective-user-test
  (testing "effective-user"
    (is (= user/*admin-user* (effective-user user/*admin-user* {}))
        "identitity if no action options")
    (is (= (assoc user/*admin-user* :no-sudo false :sudo-user "fred")
           (effective-user
            (assoc user/*admin-user* :no-sudo true)
            {:sudo-user "fred"}))
        "sudo-user in options overrides no-sudo in user")))

(deftest implement-action-test
  (is (thrown? Exception
               (eval `(implement-action b 'fred {} [action-options] nil)))
      "implement-action with non-keyword dispatch should fail to compile")
  (is (implement-action b :x {} (fn [_]))))
