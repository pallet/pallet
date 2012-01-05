(ns pallet.action-plan-test
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.argument :as argument]
   [pallet.core :as core])
  (:use
   clojure.test
   pallet.action-plan
   [pallet.node-value
    :only [make-node-value node-value node-value? set-node-value]]
   [pallet.argument :only [delayed]]))

(def add-action #'action-plan/add-action)
(def push-block #'action-plan/push-block)
(def pop-block #'action-plan/pop-block)

(deftest action-plan-test
  (let [b (add-action nil 1)
        c (add-action b 2)]
    (is (= '((1) nil) b))
    (is (= '((2 1) nil) c))))

(deftest push-block-test
  (testing "default initial block"
    (let [c (add-action nil 2)
          d (add-action c 3)
          e (pop-block d)
          ]
      (is (= '((2) nil) c))
      (is (= '((3 2) nil) d))
      (is (= '(2 3) e))))
  (testing "with additional block"
    (let [a (add-action nil {:v 1})
          b (push-block a)
          c (add-action b {:v 2})
          d (add-action c {:v 3})
          e (pop-block d)
          f (pop-block e)]
      (is (= '(({:v 1}) nil) a))
      (is (= '(nil ({:v 1}) nil) b))
      (is (= '(({:v 2}) ({:v 1}) nil) c))
      (is (= '(({:v 3} {:v 2}) ({:v 1}) nil) d))
      (is (= '(({:v 1 :blocks [({:v 2} {:v 3})]}) nil) e))
      (is (= '({:v 1 :blocks [({:v 2} {:v 3})]}) f))))
  (testing "with adjacent blocks"
    (let [a (add-action nil {:v 1})
          b (push-block a)
          c (add-action b {:v 2})
          d (add-action c {:v 3})
          e (pop-block d)
          f (push-block e)
          g (add-action f {:v 4})
          h (pop-block g)
          i (pop-block h)]
      (is (= '(({:v 1}) nil) a))
      (is (= '(nil ({:v 1}) nil) b))
      (is (= '(({:v 2}) ({:v 1}) nil) c))
      (is (= '(({:v 3} {:v 2}) ({:v 1}) nil) d))
      (is (= '(({:v 1, :blocks [({:v 2} {:v 3})]}) nil) e))
      (is (= '(nil ({:v 1, :blocks [({:v 2} {:v 3})]}) nil) f))
      (is (= '(({:v 4}) ({:v 1, :blocks [({:v 2} {:v 3})]}) nil) g))
      (is (= '(({:v 1, :blocks [({:v 2} {:v 3}) ({:v 4})]}) nil) h))
      (is (= '({:v 1, :blocks [({:v 2} {:v 3}) ({:v 4})]}) i)))))

(deftest action-map-test
  (let [action (action-map identity {:action-id 1} [] :a :b :l)]
    (is (= {:f identity :action-id 1 :args [] :action-type :b :execution :a
            :location :l :context nil}))))

(deftest walk-action-plan-test
  (let [nested-identity (fn [a b] (assoc a :blocks b))]
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
                        :args [1]
                        :blocks [[{:f identity :args [1]
                                   :action-type :script/bash}
                                  {:f identity :args [2]
                                   :action-type :script/bash}]]
                        :action-type :flow/if}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))))

(deftest group-by-function-test
  (is (= '({:f 1 :args ((0 1 2) [:a :b]) :other :a :context nil}
           {:f 3 :args ((\f \o \o)) :other :c :context nil}
           {:f 2 :args ((0 1 2) ["bar baz"]) :other :b :context nil})
         (#'action-plan/group-by-function
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
           :context ["[a]" "[b]"]}]
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
           :args []
           :blocks [[{:f identity
                      :args [[1] [2]]
                      :location :target
                      :action-type :script/bash
                      :execution :aggregated
                      :context nil}
                     {:f identity
                      :args [3]
                      :location :target
                      :action-type :script/bash
                      :execution :in-sequence}]]
           :action-type :flow/if
           :execution :in-sequence
           :location :target}]
         (#'action-plan/transform-executions
          [{:f identity
            :args []
            :blocks [[{:f identity
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
                       :execution :aggregated}]]
            :action-type :flow/if
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

(deftest augment-return-test
  (testing "script/bash"
    (let [f (fn [session x] (str x))
          action-plan [{:f #(f % 1)
                        :location :target
                        :action-type :script/bash
                        :execution :in-sequence
                        :node-value-path 'nvp}]
          action-plan (#'action-plan/augment-return-values action-plan)]
      (is (= 1 (count action-plan)))
      (is (map? (first action-plan)))
      (is (nil? (:args action-plan)))
      (is (fn? (:f (first action-plan))))
      (is (= {:value "1"
              :session {:a 1 :node-values {'nvp "1"}}
              :location :target
              :action-type :script/bash
              :execution :in-sequence
              :node-value-path 'nvp}
             (->
              {:a 1}
              ((:f (first action-plan)))
              (dissoc :f)))))))

(deftest translate-test
  (let [f (fn [session x] (str x))
        a1 (action-map f {} [1] :in-sequence :script/bash :target)
        a2 (action-map f {} [2] :in-sequence :script/bash :target)
        action-plan (-> nil
                        (add-action
                         (assoc a1 :node-value-path :v1))
                        (add-action
                         (assoc a2 :node-value-path :v2)))
        translated-plan (translate action-plan)]
    (is (=
         [{:location :target :action-type :script/bash
           :execution :in-sequence :context nil}
          {:location :target :action-type :script/bash
           :execution :in-sequence :context nil}]
         (->> translated-plan (map #(dissoc % :f :node-value-path)))))
    (is (=
         {:value "1"
          :session {:node-values {:v1 "1"}}
          :location :target
          :action-type :script/bash
          :execution :in-sequence
          :context nil}
         (->
          {}
          ((-> translated-plan first :f))
          (dissoc :f :node-value-path))))
    (is (=
         {:value "2"
          :session {:node-values {:v2 "2"}}
          :location :target
          :action-type :script/bash
          :execution :in-sequence
          :context nil}
         (->
          {}
          ((-> translated-plan second :f))
          (dissoc :f :node-value-path))))))

(defn executor [m]
  (fn [session {:keys [f action-type location] :as action}]
    (let [exec-fn (get-in
                   (merge
                    {:flow/if {:local execute-if}}
                    m)
                   [action-type location])]
      (assert exec-fn)
      (exec-fn session action))))

(defn echo
  "Echo the result of an action. Do not execute."
  [session {:keys [f]}]
  (let [rm (f session)]
    [(:value rm) (:session rm)]))

(defn null-result
  "Echo the result of an action. Do not execute."
  [session {:keys [f]}]
  (let [{:keys [value session]} (f session)]
    [nil session]))

(deftest execute-action-test
  (let [f (fn [session x] (str x))]
    (is (=
         ["1" {:node-values {'nvp "1"}}]
         (execute-action
          (executor {:script/bash {:target echo}})
          {}
          (augment-return
           {:f (fn [session] "1")
            :location :target
            :action-type :script/bash
            :execution :in-sequence
            :node-value-path 'nvp}))))))

(defn dissoc-action-plan
  [[r s]]
  [r (dissoc s :action-plan)])

(deftest execute-test
  (testing "aggregated"
    (let [f (fn [session x] (str (vec x)))
          a1 (action-map
              f {} [1] :aggregated :script/bash :target)
          a2 (action-map
              f {} [2] :aggregated :script/bash :target)
          action-plan (->
                       nil
                       (add-action
                        (assoc a1 :node-value-path :v1))
                       (add-action
                        (assoc a2 :node-value-path :v2)))]
      (is (=
           [["[(1) (2)]"] {:a 1 :node-values {:v1 "[(1) (2)]"}}]
           (-> (execute
                (translate action-plan)
                {:a 1}
                (executor {:script/bash {:target echo}})
                stop-execution-on-error)
               (dissoc-action-plan))))))
  (testing "if"
    (let [f (fn [session x] (str (vec x)))
          bv (atom true)
          expr-t (fn [session x] x)
          a0 (action-map
              expr-t {} [(delayed [_] @bv)] :in-sequence :flow/if :local)
          a1 (action-map
              f {} [1] :aggregated :script/bash :target)
          a2 (action-map
              f {} [2] :aggregated :script/bash :target)
          a3 (action-map
              f {} [3] :aggregated :script/bash :target)
          a4 (action-map
              f {} [[4]] :in-sequence :script/bash :target)
          action-plan (-> nil
                          (add-action
                           (assoc a0 :node-value-path :v0))
                          push-block
                          (add-action
                           (assoc a1 :node-value-path :v1))
                          (add-action
                           (assoc a2 :node-value-path :v2))
                          pop-block
                          (add-action
                           (assoc a3 :node-value-path :v3))
                          (add-action
                           (assoc a4 :node-value-path :v4)))]
      (testing "then"
        (is (=
             [["[(3)]" "[(1) (2)]" "[4]"]
              {:a 1
               :node-values {:v0 true :v1 "[(1) (2)]" :v3 "[(3)]" :v4 "[4]"}}]
             (->
              (execute
               (translate action-plan)
               {:a 1}
               (executor {:script/bash {:target echo}})
               stop-execution-on-error)
              (dissoc-action-plan)))))
      (testing "else"
        (reset! bv false)
        (is (=
             [["[(3)]" nil "[4]"]
              {:a 1 :node-values {:v0 false :v3 "[(3)]" :v4 "[4]"}}]
             (->
              (execute
               (translate action-plan)
               {:a 1}
               (executor {:script/bash {:target echo}})
               stop-execution-on-error)
              (dissoc-action-plan)))))))
  (testing "node-value"
    (let [f (fn [session] [1 session])
          a (action-map
             f {} [] :in-sequence :fn/clojure :target)
          action-plan (->
                       nil
                       (add-action (assoc a :node-value-path :v)))]
      (is (map? a))
      (let [[rv session :as all-rv]
            (execute
             (translate action-plan)
             {:a 1}
             (executor {:fn/clojure {:target echo}})
             stop-execution-on-error)
            nv (make-node-value :v)]
        (is (= 1 (node-value nv session)))))))

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
  (action-map f meta [] :in-sequence :script/bash :target))

(deftest action-dependencies-test
  (let [b (fn [])]
    (is (= {{:f b :action-id :id-b} #{{:f g} {:f 'gg :action-id :id-g}}
            {:action-id :id-f :f 'ff} #{{:f b :action-id :id-b}}
            {:f f} #{{:f b :action-id :id-b}}}
           (#'action-plan/action-dependencies
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
           (#'action-plan/action-scope-dependencies actions)))))

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
           (#'action-plan/enforce-scope-dependencies actions)))))

(deftest enforce-precedence-test
  (testing "reordering across execution-type"
    (let [g (fn [session x] (str x))
          a1 (action-map
              f {} [2] :aggregated :script/bash :target)
          a2 (action-map
              g  {:always-before `fx}
              [1] :in-sequence :script/bash :target)
          action-plan (-> nil
                          (add-action (assoc a1 :node-value-path :v1))
                          (add-action (assoc a2 :node-value-path :v2)))]
      (is (=
           [["1" "[(2)]"] {:node-values {:v1 "[(2)]" :v2 "1"}}]
           (->
            (execute
             (translate action-plan)
             {}
             (executor {:script/bash {:target echo}})
             stop-execution-on-error)
            (dissoc-action-plan)))))))

(deftest delayed-argument-test
  (testing "delayed arguments"
    (let [g (fn [session x] (str x))
          a (action-map
             f {}
             [(pallet.argument/delayed [session] 1)]
             :aggregated :script/bash :target)
          action-plan (->
                       nil
                       (add-action (assoc a :node-value-path :v)))]
      (is (=
           [["[(1)]"] {:node-values {:v "[(1)]"}}]
           (->
            (execute
             (translate action-plan)
             {}
             (executor {:script/bash {:target echo}})
             stop-execution-on-error)
            (dissoc-action-plan)))))))

(deftest schedule-action-test
  ;;; TODO
  ;;; check aggregated actions return the same node-value
  )
