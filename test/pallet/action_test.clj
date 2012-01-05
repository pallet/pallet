(ns pallet.action-test
  (:require
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.action-plan-test :as action-plan-test]
   [pallet.core :as core]
   [clojure.string :as string])
  (:use
   clojure.test
   [pallet.monad :only [let-s]]
   [pallet.node-value :only [node-value?]]
   [pallet.test-utils :only [test-session]]))

(deftest action-test
  (is (fn? (action/action :in-sequence :script/bash :target [session] "hello")))
  (let [f (action/action :in-sequence :script/bash :target [session] "hello")]
    (is (fn? (action/action-fn f)))
    (is (= "hello" ((action/action-fn f) {})))
    (let [[nv session] ((f)
                        {:phase :fred :target-id :id :server {:node-id :id}})]
      (is (node-value? nv))
      (is (= {:action-plan
              {:fred
               {:id [[{:f (action/action-fn f)
                       :args nil
                       :node-value-path (.path nv)
                       :location :target
                       :action-type :script/bash
                       :execution :in-sequence
                       :context nil}] nil]}}
              :phase :fred
              :target-id :id
              :server {:node-id :id}}
             session)))))

(deftest bash-action-test
  (is (fn? (action/bash-action [session arg] arg)))
  (let [f (action/bash-action [session arg] {:some-meta :a} arg)]
    (is (fn? (action/action-fn f)))
    (is (= "hello" ((action/action-fn f) {:a 1} "hello")))
    (is (= :a (:some-meta (meta f))))
    (is (= :a (:some-meta (meta (action/action-fn f)))))
    (let [[nv session] ((f "hello")
                        {:phase :fred :target-id :id :server {:node-id :id}})]
      (is (= {:action-plan
              {:fred
               {:id [[{:f (action/action-fn f)
                       :args ["hello"]
                       :node-value-path (.path nv)
                       :location :target
                       :action-type :script/bash
                       :execution :in-sequence
                       :context nil}] nil]}}
              :phase :fred
              :target-id :id
              :server {:node-id :id}}
             session)))))

(action/def-bash-action test-bash-action
  "Some doc"
  [session arg]
  {:some-meta :a}
  arg)

(deftest def-bash-action-test
  (is (= '([session arg]) (:arglists (meta #'test-bash-action))))
  (is (= "Some doc" (:doc (meta #'test-bash-action))))
  (is (= :a (:some-meta (meta test-bash-action))))
  (is (fn? (action/action-fn test-bash-action)))
  (is (= "hello" ((action/action-fn test-bash-action) {:a 1} "hello")))
  (is (= :a (:some-meta (meta (action/action-fn test-bash-action)))))
  (let [[nv session] ((test-bash-action "hello")
          {:phase :fred :target-id :id :server {:node-id :id}})]
    (is (= {:action-plan
            {:fred
             {:id [[{:f (action/action-fn test-bash-action)
                     :args ["hello"]
                     :location :target
                     :node-value-path (.path nv)
                     :action-type :script/bash
                     :execution :in-sequence
                     :context nil}] nil]}}
            :phase :fred
            :target-id :id
            :server {:node-id :id}}
           session))))

(defn dissoc-action-plan
  [[r s]]
  [r (update-in
      s [:action-plan]
      dissoc
      :pallet.action-plan/executor :pallet.action-plan/execute-status-fn)])

(deftest clj-action-test
  (is (fn? (action/clj-action [session arg] session)))
  (let [f (action/clj-action [session arg] session)]
    (is (fn? (action/action-fn f)))
    (is (= {:a 1} ((action/action-fn f) {:a 1} 1)))
    (let [[nv session] ((f 1)
                        {:phase :fred :target-id :id :server {:node-id :id}})]
      (is (= {:action-plan
              {:fred
               {:id [[{:f (action/action-fn f)
                       :args [1]
                       :location :origin
                       :node-value-path (.path nv)
                       :action-type :fn/clojure
                       :execution :in-sequence
                       :context nil}] nil]}}
              :phase :fred
              :target-id :id
              :server {:node-id :id}}
             session))))
  (testing "execute"
    (let [x (atom nil)
          f (action/clj-action [session arg] (reset! x true) [nil session])
          req {:phase :fred :target-id :id :server {:node-id :id}}
          [nv1 req] ((f 1) req)
          [nv2 req] ((f 2) req)]
      (is (map? (:action-plan req)))
      (is (= [[nil nil]
              {:action-plan
               {:fred
                {:id [[{:f (action/action-fn f)
                        :args [2]
                        :location :origin
                        :node-value-path (.path nv2)
                        :action-type :fn/clojure
                        :execution :in-sequence
                        :context nil}
                       {:f (action/action-fn f)
                        :args [1]
                        :location :origin
                        :node-value-path (.path nv1)
                        :action-type :fn/clojure
                        :execution :in-sequence
                        :context nil}]
                      nil]}}
               :phase :fred
               :target-id :id
               :server {:node-id :id}
               :node-values {(.path nv1) nil, (.path nv2) nil}, }]
             (->
              (action-plan/execute
                  (action-plan/translate (-> req :action-plan :fred :id))
                  req
                  (action-plan-test/executor
                   {:script/bash {:target action-plan-test/echo}
                    :fn/clojure {:origin action-plan-test/null-result}})
                  (:execute-status-fn core/default-algorithms))
              (dissoc-action-plan))))
      (is @x))))

(deftest as-clj-action-test
  (testing "with named args"
    (is (fn? (action/as-clj-action identity [session])))
    (let [f (action/as-clj-action identity [session])]
      (is (fn? (action/action-fn f)))
      (is (= [{:a 1} {:a 1}] ((action/action-fn f) {:a 1})))
      (let [[nv session] ((f)
                          {:phase :fred :target-id :id :server {:node-id :id}})]
        (is (= {:action-plan
                {:fred
                 {:id [[{:f (action/action-fn f)
                         :args nil
                         :location :origin
                         :node-value-path (.path nv)
                         :action-type :fn/clojure
                         :execution :in-sequence
                         :context nil}] nil]}}
                :phase :fred
                :target-id :id
                :server {:node-id :id}}
               session)))))
  (testing "with implied args"
    (is (fn? (action/as-clj-action identity)))
    (let [f (action/as-clj-action identity)]
      (is (fn? (action/action-fn f)))
      (is (= [{:a 1} {:a 1}] ((action/action-fn f) {:a 1})))
      (let [[nv session] ((f)
                          {:phase :fred :target-id :id
                           :server {:node-id :id}})]
        (is (= {:action-plan
                {:fred
                 {:id [[{:f (action/action-fn f)
                         :args nil
                         :location :origin
                         :node-value-path (.path nv)
                         :action-type :fn/clojure
                         :execution :in-sequence
                         :context nil}] nil]}}
                :phase :fred
                :target-id :id
                :server {:node-id :id}}
               session))))))

(deftest aggregated-action-test
  (is (fn? (action/aggregated-action [session args] (vec args))))
  (let [f (action/aggregated-action [session args] (vec args))]
    (is (fn? (action/action-fn f)))
    (is (= [1] ((action/action-fn f) {:a 1} [1])))
    (let [req {:phase :fred :target-id :id :server {:node-id :id}}
          [nv1 req] ((f 1) req)
          [nv2 req] ((f 2) req)]
      (is (map? req))
      (is (= (.path nv1) (.path nv2)))
      (is (= [[[[1] [2]]]
              {:action-plan
               {:fred
                {:id [[{:f (action/action-fn f)
                        :args [2]
                        :location :target
                        :node-value-path (.path nv2)
                        :action-type :script/bash
                        :execution :aggregated
                        :context nil}
                       {:f (action/action-fn f)
                        :args [1]
                        :location :target
                        :node-value-path (.path nv1)
                        :action-type :script/bash
                        :execution :aggregated
                        :context nil}]
                      nil]}}
               :phase :fred
               :target-id :id
               :server {:node-id :id}
               :node-values {(.path nv1) [[1] [2]]}}]
             (->
              (action-plan/execute
               (action-plan/translate (-> req :action-plan :fred :id))
               req
               (action-plan-test/executor
                {:script/bash {:target action-plan-test/echo}
                 :fn/clojure {:origin action-plan-test/null-result}})
               (:execute-status-fn core/default-algorithms))
              (dissoc-action-plan)))))))


(action/def-aggregated-action
  test-aggregated-action
  "Some doc"
  [session arg]
  {:arglists '([session arg1])
   :action-id :a}
  (string/join (map first arg)))

(deftest def-aggregated-action-test
  (is (= '([session arg1]) (:arglists (meta #'test-aggregated-action))))
  (is (= :a (:action-id (meta #'test-aggregated-action))))
  (is (= "Some doc" (:doc (meta #'test-aggregated-action))))
  (is (= :a (:action-id (meta test-aggregated-action))))
  (is (fn? (action/action-fn test-aggregated-action)))
  (is (= "hello"
         ((action/action-fn test-aggregated-action) {:a 1} [["hello"]])))
  (is (= :a (:action-id (meta (action/action-fn test-aggregated-action)))))
  (let [[nv session] ((test-aggregated-action "hello")
                      {:phase :fred :target-id :id :server {:node-id :id}})]
    (is (= {:action-plan
            {:fred
             {:id [[{:f (action/action-fn test-aggregated-action)
                     :action-id :a
                     :args ["hello"]
                     :location :target
                     :node-value-path (.path nv)
                     :action-type :script/bash
                     :execution :aggregated
                     :context nil}] nil]}}
            :phase :fred
            :target-id :id
            :server {:node-id :id}}
           session))))

(defn echo
  "Echo the result of an action. Do not execute."
  [session f]
  [(:value (f session)) session])

(defn executor [m]
  (fn [session {:keys [f action-type location]}]
    (let [exec-fn (get-in m [action-type location])]
      (assert exec-fn)
      (exec-fn session f))))

(deftest with-precedence-test
  (let [session (test-session
                 {:phase :fred :target-id :id :server {:node-id :id}})
        p (let-s [_ (test-aggregated-action "hello")
                  _ (action/with-precedence
                      {:always-before #{`test-aggregated-action}}
                      (test-bash-action "a"))]
                 nil)
        [_ session] (p session)]
    (is (=
         ["a" "hello"]
         (first
          (action-plan/execute
           (action-plan/translate
            (get-in session (action-plan/target-path session)))
           session
           (executor {:script/bash {:target echo}})
           (:execute-status-fn core/default-algorithms)))))))
