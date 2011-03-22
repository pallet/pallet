(ns pallet.action-plan-test
  (:require
   [pallet.action-plan :as action-plan])
  (:use
   clojure.test))

(deftest action-plan-test
  (let [b (action-plan/add-action nil 1)
        c (action-plan/add-action b 2)]
    (is (= '((1) nil) b))
    (is (= '((2 1) nil) c))))

(deftest push-block-test
  (testing "default initial block"
    (let [c (action-plan/add-action nil 2)
          d (action-plan/add-action c 3)
          e (action-plan/pop-block d)
          ]
      (is (= '((2) nil) c))
      (is (= '((3 2) nil) d))
      (is (= '(2 3) e))))
  (testing "with additional block"
    (let [a (action-plan/add-action nil 1)
          b (action-plan/push-block a)
          c (action-plan/add-action b 2)
          d (action-plan/add-action c 3)
          e (action-plan/pop-block d)
          f (action-plan/pop-block e)]
      (is (= '((1) nil) a))
      (is (= '(nil (1) nil) b))
      (is (= '((2) (1) nil) c))
      (is (= '((3 2) (1) nil) d))
      (is (= '(((2 3) 1) nil) e))
      (is (= '(1 (2 3)) f))))
  (testing "with additional initialblock"
    (let [b (action-plan/push-block nil)
          c (action-plan/add-action b 2)
          d (action-plan/add-action c 3)
          e (action-plan/pop-block d)
          f (action-plan/add-action e 4)
          g (action-plan/pop-block f)]
      (is (= '(nil nil nil) b))
      (is (= '((2) nil nil) c))
      (is (= '((3 2) nil nil) d))
      (is (= '(((2 3)) nil) e))
      (is (= '((4 (2 3)) nil) f))
      (is (= '((2 3) 4) g)))))

(deftest action-map-test
  (is (= {:f identity :args [] :action-type :b :execution :a :location :l}
         (action-plan/action-map identity [] :a :b :l))))


(deftest walk-action-plan-test
  (let [nested-identity (fn [a _] a)]
    (let [action-plan [{:f identity :args [1] :action-type :script/bash}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))
    (let [action-plan [{:f identity :args [1] :action-type :script/bash}
                       {:f identity :args [2] :action-type :script/bash}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))
    (let [action-plan [{:f identity :args [1] :action-type :script/bash}
                       {:f identity :args [2] :action-type :script/bash}
                       {:f identity
                        :args [{:f identity :args [1]
                                :action-type :script/bash}
                               {:f identity :args [2]
                                :action-type :script/bash}]
                        :action-type :nested-scope}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))))

(deftest transform-scopes-test
  (let [f (fn [request x] (str x))]
    (testing "unnested"
      (let [action-plan (-> nil
                            (action-plan/add-action
                             (action-plan/action-map
                              f [1] :in-sequence :script/bash :target))
                            (action-plan/add-action
                             (action-plan/action-map
                              f [2] :in-sequence :script/bash :target)))]
        (is (=
             [{:f f :args [1] :location :target :action-type
               :script/bash :execution :in-sequence}
              {:f f :args [2] :location :target :action-type :script/bash
               :execution :in-sequence}]
             (->>
              action-plan
              action-plan/pop-block
              (#'action-plan/transform-scopes))))))
    (testing "nested"
      (let [action-plan (-> nil
                            action-plan/push-block
                            (action-plan/add-action
                             (action-plan/action-map
                              f [1] :in-sequence :script/bash :target))
                            (action-plan/add-action
                             (action-plan/action-map
                              f [2] :in-sequence :script/bash :target))
                            action-plan/pop-block)]
        (is (=
             [{:f (var-get #'action-plan/scope-action)
               :args [{:f f :args [1]
                       :location :target :action-type :script/bash
                       :execution :in-sequence}
                      {:f f :args [2]
                       :location :target :action-type :script/bash
                       :execution :in-sequence}]
               :action-type :nested-scope
               :execution :in-sequence
               :location :target}]
             (->>
              action-plan
              action-plan/pop-block
              (#'action-plan/transform-scopes))))))))

(deftest group-by-function-test
  (is (= '({:f 1 :args ((0 1 2) [:a :b]) :other :a}
           {:f 3 :args ((\f \o \o)) :other :c}
           {:f 2 :args ((0 1 2) ["bar baz"]) :other :b})
         (#'pallet.action-plan/group-by-function
          [{:f 1 :args (range 3) :other :a}
           {:f 3 :args (seq "foo") :other :c}
           {:f 2 :args (range 3) :other :b}
           {:f 2 :args ["bar baz"] :other :b}
           {:f 1 :args [:a :b] :other :a}]))))


(deftest transform-executions-test
  (testing "aggregated"
    (is (=
         [{:f identity
           :args [[1] [2]]
           :location :target
           :action-type :script/bash
           :execution :aggregated}]
         (#'action-plan/transform-executions
          [{:f identity
            :args [1]
            :location :target
            :action-type :script/bash
            :execution :aggregated}
           {:f identity
            :args [2]
            :location :target
            :action-type :script/bash
            :execution :aggregated}]))))
  (testing "mixed"
    (is (=
         [{:f identity
           :args [[1] [2]]
           :location :target
           :action-type :script/bash
           :execution :aggregated}
          {:f identity
           :args [3]
           :location :target
           :action-type :script/bash
           :execution :in-sequence}]
         (#'action-plan/transform-executions
          [{:f identity
            :args [1]
            :location :target
            :action-type :script/bash
            :execution :aggregated}
           {:f identity
            :args [3]
            :location :target
            :action-type :script/bash
            :execution :in-sequence}
           {:f identity
            :args [2]
            :location :target
            :action-type :script/bash
            :execution :aggregated}]))))
  (testing "nested"
    (is (=
         [{:f identity
           :args [{:f identity
                   :args [[1] [2]]
                   :location :target
                   :action-type :script/bash
                   :execution :aggregated}
                  {:f identity
                   :args [3]
                   :location :target
                   :action-type :script/bash
                   :execution :in-sequence}]
           :action-type :nested-scope
           :execution :in-sequence
           :location :target}]
         (#'action-plan/transform-executions
          [{:f identity
            :args [{:f identity
                    :args [1]
                    :location :target
                    :action-type :script/bash
                    :execution :aggregated}
                   {:f identity
                    :args [3]
                    :location :target
                    :action-type :script/bash
                    :execution :in-sequence}
                   {:f identity
                    :args [2]
                    :location :target
                    :action-type :script/bash
                    :execution :aggregated}]
            :action-type :nested-scope
            :execution :in-sequence
            :location :target}])))))



(deftest bind-arguments-test
  (testing "in-sequence"
    (let [f (fn [request x] x)
          action-plan (#'action-plan/bind-arguments
                       [{:f f
                         :args [1]
                         :location :target
                         :action-type :script/bash
                         :execution :in-sequence}])]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= 1 ((:f (first action-plan)) {})))))
  (testing "aggregated"
    (let [f (fn [request x] x)
          action-plan (#'action-plan/bind-arguments
                       [{:f f
                         :args [[1] [2]]
                         :location :target
                         :action-type :script/bash
                         :execution :aggregated}])]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= [[1] [2]] ((:f (first action-plan)) {})))))
    (testing "collected"
    (let [f (fn [request x] x)
          action-plan (#'action-plan/bind-arguments
                       [{:f f
                         :args [[1] [2]]
                         :location :target
                         :action-type :script/bash
                         :execution :collected}])]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= [[1] [2]] ((:f (first action-plan)) {}))))))

(deftest combine-by-location-and-type-test
  (testing "script/bash"
    (let [f (fn [request x] (str x))
          action-plan [{:f f
                        :args [1]
                        :location :target
                        :action-type :script/bash
                        :execution :in-sequence}
                       {:f f
                        :args [2]
                        :location :target
                        :action-type :script/bash
                        :execution :in-sequence}]
          action-plan (#'action-plan/bind-arguments action-plan)
          action-plan (#'action-plan/combine-by-location-and-action-type
                       action-plan)]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= "1\n2\n" ((:f (first action-plan)) {}))))))

(deftest augment-return-test
  (testing "script/bash"
    (let [f (fn [request x] (str x))
          action-plan [{:f #(f % 1)
                        :location :target
                        :action-type :script/bash
                        :execution :in-sequence}]
          action-plan (#'action-plan/augment-return-values action-plan)]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= {:value "1"
              :request {:a 1}
              :location :target
              :action-type :script/bash
              :execution :in-sequence}
             (-> ((:f (first action-plan)) {:a 1})
                 (dissoc :f)))))))

(deftest translate-test
  (let [f (fn [request x] (str x))
        action-plan (-> nil
                        (action-plan/add-action
                         (action-plan/action-map
                          f [1] :in-sequence :script/bash :target))
                        (action-plan/add-action
                         (action-plan/action-map
                          f [2] :in-sequence :script/bash :target)))]
    (is (=
         [{:location :target :action-type :script/bash :execution :in-sequence}]
         (->>
          (action-plan/translate action-plan)
          (map #(dissoc % :f)))))
    (is (=
         {:value "1\n2\n",
          :request {}
          :location :target
          :action-type :script/bash
          :execution :in-sequence}
         (->
          ((-> (action-plan/translate action-plan) first :f) {})
          (dissoc :f))))))

(defn executor [m]
  (fn [request f action-type location]
    (let [exec-fn (get-in m [action-type location])]
      (assert exec-fn)
      (exec-fn request f))))

(defn echo
  "Echo the result of an action. Do not execute."
  [request f]
  [(:value (f request)) request])

(defn null-result
  "Echo the result of an action. Do not execute."
  [request f]
  (let [{:keys [request]} (f request)]
    [nil request]))

(deftest excute-action-test
  (let [f (fn [request x] (str x))]
    (is (=
         [["1"] {}]
         (action-plan/execute-action
          (executor {:script/bash {:target echo}})
          [[] {}]
          (action-plan/augment-return
           {:f (fn [request] "1")
            :location :target
            :action-type :script/bash
            :execution :in-sequence}))))))

(deftest execute-test
  (let [f (fn [request x] (str (vec x)))
        action-plan (-> nil
                        (action-plan/add-action
                         (action-plan/action-map
                          f [1] :aggregated :script/bash :target))
                        (action-plan/add-action
                         (action-plan/action-map
                          f [2] :aggregated :script/bash :target)))]
    (is (=
         [["[(1) (2)]\n"] {:a 1}]
         (action-plan/execute
          (action-plan/translate action-plan)
          {:a 1}
          (executor {:script/bash {:target echo}})))))
  (testing "nested"
    (let [f (fn [request x] (str (vec x)))
          action-plan (-> nil
                          action-plan/push-block
                          (action-plan/add-action
                           (action-plan/action-map
                            f [1] :aggregated :script/bash :target))
                          (action-plan/add-action
                           (action-plan/action-map
                            f [2] :aggregated :script/bash :target))
                          action-plan/pop-block
                          (action-plan/add-action
                           (action-plan/action-map
                            f [3] :aggregated :script/bash :target))
                          (action-plan/add-action
                           (action-plan/action-map
                            f [[4]] :in-sequence :script/bash :target)))]
      (is (=
           [["[(3)]\n" "[(1) (2)]\n" "[4]\n"] {:a 1}]
           (action-plan/execute
            (action-plan/translate action-plan)
            {:a 1}
            (executor {:script/bash {:target echo}})))))))
