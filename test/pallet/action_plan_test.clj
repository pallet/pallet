(ns pallet.action-plan-test
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.argument :as argument]
   [pallet.core :as core])
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
  (testing "with adjacent blocks"
    (let [a (action-plan/add-action nil 1)
          b (action-plan/push-block a)
          c (action-plan/add-action b 2)
          d (action-plan/add-action c 3)
          e (action-plan/pop-block d)
          f (action-plan/push-block e)
          g (action-plan/add-action f 4)
          h (action-plan/pop-block g)
          i (action-plan/pop-block h)]
      (is (= '((1) nil) a))
      (is (= '(nil (1) nil) b))
      (is (= '((2) (1) nil) c))
      (is (= '((3 2) (1) nil) d))
      (is (= '(((2 3) 1) nil) e))
      (is (= '(nil ((2 3) 1) nil) f))
      (is (= '((4) ((2 3) 1) nil) g))
      (is (= '(((4) (2 3) 1) nil) h))
      (is (= '(1 (2 3) (4)) i))))
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
  (is (= {:f identity :action-id 1 :args [] :action-type :b :execution :a
          :location :l :context nil}
         (action-plan/action-map identity {:action-id 1} [] :a :b :l))))


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
  (let [f (fn [session x] (str x))]
    (testing "unnested"
      (let [action-plan (-> nil
                            (action-plan/add-action
                             (action-plan/action-map
                              f {} [1] :in-sequence :script/bash :target))
                            (action-plan/add-action
                             (action-plan/action-map
                              f {} [2] :in-sequence :script/bash :target)))]
        (is (=
             [{:f f :args [1] :location :target
               :action-type :script/bash :execution :in-sequence :context nil}
              {:f f :args [2] :location :target
               :action-type :script/bash :execution :in-sequence :context nil}]
             (->>
              action-plan
              action-plan/pop-block
              (#'action-plan/transform-scopes))))))
    (testing "nested"
      (let [action-plan (-> nil
                            action-plan/push-block
                            (action-plan/add-action
                             (action-plan/action-map
                              f nil [1] :in-sequence :script/bash :target))
                            (action-plan/add-action
                             (action-plan/action-map
                              f nil [2] :in-sequence :script/bash :target))
                            action-plan/pop-block)]
        (is (=
             [{:f (var-get #'action-plan/scope-action)
               :args [{:f f
                       :args [1]
                       :location :target :action-type :script/bash
                       :execution :in-sequence :context nil}
                      {:f f
                       :args [2]
                       :location :target :action-type :script/bash
                       :execution :in-sequence :context nil}]
               :action-type :nested-scope
               :execution :in-sequence
               :location :target}]
             (->>
              action-plan
              action-plan/pop-block
              (#'action-plan/transform-scopes))))))))

(deftest group-by-function-test
  (is (= '({:f 1 :args ((0 1 2) [:a :b]) :other :a :context nil}
           {:f 3 :args ((\f \o \o)) :other :c :context nil}
           {:f 2 :args ((0 1 2) ["bar baz"]) :other :b :context nil})
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
           :execution :aggregated
           :context ["[a] " "[b] "]}]
         (#'action-plan/transform-executions
          [{:f identity
            :args [1]
            :location :target
            :action-type :script/bash
            :execution :aggregated
            :context ["a"]}
           {:f identity
            :args [2]
            :location :target
            :action-type :script/bash
            :execution :aggregated
            :context ["b"]}]))))
  (testing "mixed"
    (is (=
         [{:f identity
           :args [[1] [2]]
           :location :target
           :action-type :script/bash
           :execution :aggregated
           :context nil}
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
                   :execution :aggregated
                   :context nil}
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
    (let [f (fn [session x] x)
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
    (let [f (fn [session x] x)
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
    (let [f (fn [session x] x)
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
    (let [f (fn [session x] (str x))
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
    (let [f (fn [session x] (str x))
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
              :session {:a 1}
              :location :target
              :action-type :script/bash
              :execution :in-sequence}
             (-> ((:f (first action-plan)) {:a 1})
                 (dissoc :f)))))))

(deftest translate-test
  (let [f (fn [session x] (str x))
        action-plan (-> nil
                        (action-plan/add-action
                         (action-plan/action-map
                          f {} [1] :in-sequence :script/bash :target))
                        (action-plan/add-action
                         (action-plan/action-map
                          f {} [2] :in-sequence :script/bash :target)))]
    (is (=
         [{:location :target :action-type :script/bash
           :execution :in-sequence :context nil}]
         (->>
          (action-plan/translate action-plan)
          (map #(dissoc % :f)))))
    (is (=
         {:value "1\n2\n"
          :session {}
          :location :target
          :action-type :script/bash
          :execution :in-sequence
          :context nil}
         (->
          ((-> (action-plan/translate action-plan) first :f) {})
          (dissoc :f))))))

(defn executor [m]
  (fn [session f action-type location]
    (let [exec-fn (get-in m [action-type location])]
      (assert exec-fn)
      (exec-fn session f))))

(defn echo
  "Echo the result of an action. Do not execute."
  [session f]
  [(:value (f session)) session])

(defn null-result
  "Echo the result of an action. Do not execute."
  [session f]
  (let [{:keys [session]} (f session)]
    [nil session]))

(deftest execute-action-test
  (let [f (fn [session x] (str x))]
    (is (=
         ["1" {}]
         (action-plan/execute-action
          (executor {:script/bash {:target echo}})
          {}
          (action-plan/augment-return
           {:f (fn [session] "1")
            :location :target
            :action-type :script/bash
            :execution :in-sequence}))))))

(deftest execute-test
  (let [f (fn [session x] (str (vec x)))
        action-plan (-> nil
                        (action-plan/add-action
                         (action-plan/action-map
                          f {} [1] :aggregated :script/bash :target))
                        (action-plan/add-action
                         (action-plan/action-map
                          f {} [2] :aggregated :script/bash :target)))]
    (is (=
         [["[(1) (2)]\n"] {:a 1} :continue]
         (action-plan/execute
          (action-plan/translate action-plan)
          {:a 1}
          (executor {:script/bash {:target echo}})
          core/stop-execution-on-error))))
  (testing "nested"
    (let [f (fn [session x] (str (vec x)))
          action-plan (-> nil
                          action-plan/push-block
                          (action-plan/add-action
                           (action-plan/action-map
                            f {} [1] :aggregated :script/bash :target))
                          (action-plan/add-action
                           (action-plan/action-map
                            f {} [2] :aggregated :script/bash :target))
                          action-plan/pop-block
                          (action-plan/add-action
                           (action-plan/action-map
                            f {} [3] :aggregated :script/bash :target))
                          (action-plan/add-action
                           (action-plan/action-map
                            f {} [[4]] :in-sequence :script/bash :target)))]
      (is (=
           [["[(3)]\n" "[(1) (2)]\n" "[4]\n"] {:a 1} :continue]
           (action-plan/execute
            (action-plan/translate action-plan)
            {:a 1}
            (executor {:script/bash {:target echo}})
            core/stop-execution-on-error))))))

;;; stubs for action precedence testing
(def f (fn [session x] (str (vec x))))
(def fx ^{:pallet.action/action-fn f} {})
(def g (fn [session x] (str (vec x))))
(def gx ^{:pallet.action/action-fn g} {})
(def h (fn [session x] (str (vec x))))
(def hx ^{:pallet.action/action-fn h} {})

(deftest symbol-action-fn-test
  (is (= f (#'action-plan/symbol-action-fn `fx)))
  (is (= g (#'action-plan/symbol-action-fn `gx))))

(deftest collect-action-id-test
  (is (= {:j 1 :k 2}
         (#'action-plan/collect-action-id
          {:k 2} {:action-id :j :always-before :fred :f 1}))))

(defn test-action-map
  [f meta]
  (action-plan/action-map f meta [] :in-sequence :script/bash :target))

(deftest action-dependencies-test
  (let [b (fn [])]
    (is (= {{:f b :action-id :id-b} #{{:f g} {:f 'gg :action-id :id-g}}
            {:action-id :id-f :f 'ff} #{{:f b :action-id :id-b}}
            {:f f} #{{:f b :action-id :id-b}}}
           (action-plan/action-dependencies
            {:id-f 'ff :id-g 'gg}
            (test-action-map
             b {:always-before #{:id-f `fx}
                :always-after #{:id-g `gx}
                :action-id :id-b}))))))

(deftest action-scope-dependencies-test
  (let [action-f (test-action-map
                  f {:always-after #{`gx}
                     :action-id :id-f})
        action-g (test-action-map g {})
        action-h (test-action-map
                  h {:always-after #{`gx}
                     :always-before #{:id-f}
                     :action-id :id-h})
        actions [action-f action-g action-h]]
    (is (= [{:id-h h :id-f f}
            {{:f h :action-id :id-h} #{{:f g}}
             {:action-id :id-f :f f} #{{:f h :action-id :id-h}{:f g}}}
            {{:f h :action-id :id-h} #{action-h}
             {:f g} #{action-g}}
            {{:f h :action-id :id-h} #{action-g}
             {:action-id :id-f :f f} #{action-h action-g}}]
           (action-plan/action-scope-dependencies actions)))))

(deftest enforce-scope-dependencies-test
  (let [action-f (test-action-map
                  f {:always-after #{`gx}
                     :action-id :id-f})
        action-g (test-action-map g {})
        action-h (test-action-map
                  h {:always-after #{`gx}
                     :always-before #{:id-f}})
        actions [action-f action-g action-h]]
    (is (= [action-g action-h action-f]
           (action-plan/enforce-scope-dependencies actions)))))

(deftest enforce-precedence-test
  (testing "reordering across execution-type"
    (let [g (fn [session x] (str x))
          action-plan (-> nil
                          (action-plan/add-action
                           (action-plan/action-map
                            f {} [2] :aggregated :script/bash :target))
                          (action-plan/add-action
                           (action-plan/action-map
                            g  {:always-before `fx}
                            [1] :in-sequence :script/bash :target)))]
      (is (=
           [["1\n[(2)]\n"] {} :continue]
           (action-plan/execute
            (action-plan/translate action-plan)
            {}
            (executor {:script/bash {:target echo}})
            core/stop-execution-on-error))))))

(deftest delayed-argument-test
  (testing "delayed arguments"
    (let [g (fn [session x] (str x))
          action-plan (-> nil
                          (action-plan/add-action
                           (action-plan/action-map
                            f {}
                            [(pallet.argument/delayed [session] 1)]
                            :aggregated :script/bash :target)))]
      (is (=
           [["[(1)]\n"] {} :continue]
           (action-plan/execute
            (action-plan/translate action-plan)
            {}
            (executor {:script/bash {:target echo}})
            core/stop-execution-on-error))))))
