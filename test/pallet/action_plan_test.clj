(ns pallet.action-plan-test
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.argument :as argument]
   [pallet.core :as core])
  (:use
   clojure.test
   pallet.action-impl
   pallet.action-plan
   [pallet.action :only [declare-action implement-action*]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.context :only [with-phase-context]]
   [pallet.node-value
    :only [make-node-value node-value node-value? set-node-value]]
   [pallet.argument :only [delayed]]))

(use-fixtures :once (logging-threshold-fixture))

(def add-action-map #'action-plan/add-action-map)

(deftest action-plan-test
  (let [b (add-action-map nil 1)
        c (add-action-map b 2)]
    (is (= '((1) nil) b))
    (is (= '((2 1) nil) c))))

(deftest push-block-test
  (testing "default initial block"
    (let [c (add-action-map nil 2)
          d (add-action-map c 3)
          e (pop-block d)
          ]
      (is (= '((2) nil) c))
      (is (= '((3 2) nil) d))
      (is (= '(2 3) e))))
  (testing "with additional block"
    (let [a (add-action-map nil {:v 1})
          b (push-block a)
          c (add-action-map b {:v 2})
          d (add-action-map c {:v 3})
          e (pop-block d)
          f (pop-block e)]
      (is (= '(({:v 1}) nil) a))
      (is (= '(nil ({:v 1}) nil) b))
      (is (= '(({:v 2}) ({:v 1}) nil) c))
      (is (= '(({:v 3} {:v 2}) ({:v 1}) nil) d))
      (is (= '(({:v 1 :blocks [({:v 2} {:v 3})]}) nil) e))
      (is (= '({:v 1 :blocks [({:v 2} {:v 3})]}) f))))
  (testing "with adjacent blocks"
    (let [a (add-action-map nil {:v 1})
          b (push-block a)
          c (add-action-map b {:v 2})
          d (add-action-map c {:v 3})
          e (pop-block d)
          f (push-block e)
          g (add-action-map f {:v 4})
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
  (let [action (make-action 'n :aggregated {:action-id 1})
        action-map (action-map action [] {:action-id 1})]
    (is (= {:action action :action-id 1 :args [] :context nil}
           action-map))))

(deftest action-map-execution-test
  (is (= :aggregated
         (action-map-execution {:action (make-action 'a :aggregated {})}))))

(deftest walk-action-plan-test
  (let [nested-identity (fn [a b] (assoc a :blocks b))]
    (let [action-plan [{:f identity :args [1] :action-type :script}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))
    (let [action-plan [{:f identity :args [1] :action-type :script}
                       {:f identity :args [2] :action-type :script}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))
    (let [action-plan [{:f identity :args [1] :action-type :script}
                       {:f identity :args [2] :action-type :script}
                       {:f identity
                        :args [1]
                        :blocks [[{:f identity :args [1]
                                   :action-type :script}
                                  {:f identity :args [2]
                                   :action-type :script}]]
                        :action-type :flow/if}]]
      (is (= action-plan
             (#'action-plan/walk-action-plan
              identity identity nested-identity action-plan))))))

(deftest group-by-function-test
  (is (= '({:action 1 :args ((0 1 2) [:a :b]) :other :a :context nil}
           {:action 3 :args ((\f \o \o)) :other :c :context nil}
           {:action 2 :args ((0 1 2) ["bar baz"]) :other :b :context nil})
         (#'action-plan/group-by-function
          [{:action 1 :args (range 3) :other :a}
           {:action 3 :args (seq "foo") :other :c}
           {:action 2 :args (range 3) :other :b}
           {:action 2 :args ["bar baz"] :other :b}
           {:action 1 :args [:a :b] :other :a}]))))

(deftest transform-executions-test
  (testing "aggregated"
    (let [action (make-action 'a0 :aggregated {})]
      (is (=
           [{:action action :args [[1] [2]] :context ["[a]" "[b]"]}]
           (#'action-plan/transform-executions
            [{:action action :args [1] :context ["a"]}
             {:action action :args [2] :context ["b"]}])))))
  (testing "mixed"
    (let [aggregated (make-action 'a0 :aggregated {})
          in-sequence (make-action 'a1 :in-sequence {})]
      (is (=
           [{:action aggregated :args [[1] [2]] :context nil}
            {:action in-sequence :args [3]}]
           (#'action-plan/transform-executions
            [{:action aggregated :args [1]}
             {:action in-sequence :args [3]}
             {:action aggregated :args [2]}])))))
  (testing "nested"
    (let [aggregated (make-action 'a0 :aggregated {})
          in-sequence (make-action 'a1 :in-sequence {})
          block (make-action 'ab :in-sequence {})]
      (is (=
           [{:action block
             :args []
             :blocks [[{:action aggregated
                        :args [[1] [2]]
                        :context nil}
                       {:action in-sequence
                        :args [3]}]]}]
           (#'action-plan/transform-executions
            [{:action block
              :args []
              :blocks [[{:action aggregated
                         :args [1]}
                        {:action in-sequence
                         :args [3]}
                        {:action aggregated
                         :args [2]}]]}]))))))

(deftest translate-test
  (let [f (make-action 'f :in-sequence {})
        _ (add-action-implementation!
           f :default {} (fn [session x] [(str x) session]))
        a1 (action-map f [1] {})
        a2 (action-map f [2] {})
        action-plan (-> nil
                        (add-action-map
                         (assoc a1 :node-value-path :v1))
                        (add-action-map
                         (assoc a2 :node-value-path :v2)))
        translated-plan (translate action-plan {})]
    (is (=
         [{:action f :args [1] :context nil}
          {:action f :args [2] :context nil}]
         (->> translated-plan (map #(dissoc % :impls :node-value-path)))))))

(defn executor
  "An executor for running test actions."
  [dispatch-val]
  (fn [session {:keys [action args] :as action-map}]
    (let [{:keys [f metadata]} (action-implementation
                                action dispatch-val :default)]
      (assert f)
      (apply f session args))))

(deftest execute-action-map-test
  (let [f (fn [session] ["1" session])
        action (make-action 'a :in-sequence {})]
    (add-action-implementation! action :default {} f)
    (is (=
         ["1" {:node-values {'nvp "1"}}]
         (execute-action-map
          (executor :default)
          {}
          {:action action :node-value-path 'nvp})))))

(defn dissoc-action-plan
  [[r s]]
  [r (dissoc s :action-plan)])

(deftest execute-test
  (testing "aggregated"
    (let [f (make-action 'f :aggregated {})
          _ (add-action-implementation!
             f :default {} (fn [session & x] [(str (vec x)) session]))
          a1 (action-map f [1] {})
          a2 (action-map f [2] {})
          action-plan (->
                       nil
                       (add-action-map
                        (assoc a1 :node-value-path :v1))
                       (add-action-map
                        (assoc a2 :node-value-path :v2)))]
      (is (=
           [["[(1) (2)]"] {:a 1 :node-values {:v1 "[(1) (2)]"}}]
           (-> (execute
                (translate action-plan {})
                {:a 1}
                (executor :default)
                stop-execution-on-error)
               (dissoc-action-plan))))))
  (testing "if"
    (let [f (make-action 'f :aggregated {})
          _ (add-action-implementation!
             f :default {} (fn [session & x] [(str (vec x)) session]))
          fi (make-action 'fi :in-sequence {})
          _ (add-action-implementation!
             fi :default {} (fn [session x] [(str x) session]))
          bv (atom true)
          b (make-action 'b :in-sequence {})
          _ (add-action-implementation!
             b :default {} (fn [session x] [x session]))
          a0 (action-map b [(delayed [_] @bv)] {})
          a1 (action-map f [1] {})
          a2 (action-map f [2] {})
          a3 (action-map f [3] {})
          a4 (action-map fi [[4]] {})
          action-plan (-> nil
                          (add-action-map (assoc a0 :node-value-path :v0))
                          push-block
                          (add-action-map (assoc a1 :node-value-path :v1))
                          (add-action-map (assoc a2 :node-value-path :v2))
                          pop-block
                          (add-action-map (assoc a3 :node-value-path :v3))
                          (add-action-map (assoc a4 :node-value-path :v4)))]
      (testing "then"
        (is (=
             [["[(3)]" true "[4]"] ; "[(1) (2)]"
              {:a 1
               :node-values {:v0 true :v3 "[(3)]" :v4 "[4]"}}] ; :v1 "[(1) (2)]"
             (->
              (execute
               (translate action-plan {})
               {:a 1}
               (executor :default)
               stop-execution-on-error)
              (dissoc-action-plan)))))
      (testing "else"
        (reset! bv false)
        (is (=
             [["[(3)]" false "[4]"]
              {:a 1 :node-values {:v0 false :v3 "[(3)]" :v4 "[4]"}}]
             (->
              (execute
               (translate action-plan {})
               {:a 1}
               (executor :default)
               stop-execution-on-error)
              (dissoc-action-plan)))))))
  (testing "node-value"
    (let [f (make-action 'f :in-sequence {})
          _ (add-action-implementation!
             f :default {} (fn [session] [1 session]))
          a (action-map f [] {})
          action-plan (->
                       nil
                       (add-action-map (assoc a :node-value-path :v)))]
      (is (map? a))
      (let [[rv session :as all-rv]
            (execute
             (translate action-plan {})
             {:a 1}
             (executor :default)
             stop-execution-on-error)
            nv (make-node-value :v)]
        (is (= 1 (node-value nv session)))))))

;;; stubs for action precedence testing
(def fx (declare-action 'f {}))
(def f (fn [session x] [(str (vec x)) session]))
(implement-action* fx :default {} f)

(def gx (declare-action 'g {}))
(def g (fn [session & x] [(str (vec x)) session]))
(implement-action* gx :default {} g)

(def hx (declare-action 'h {}))
(def h (fn [session x] [(str (vec x)) session]))
(implement-action* hx :default {} h)

(deftest assoc-action-id-test
  (is (= {:j 'a :k 2}
         (#'action-plan/assoc-action-id
          {:k 2}
          {:action-id :j :always-before :fred
           :action (make-action 'a :in-sequence {})}))))

(defn test-action-and-map
  [metadata]
  (let [inserter (declare-action 'test-action-map (dissoc metadata :action-id))
        action (-> inserter meta :action)]
    [action
     (action-map action [] metadata)]))

(defn test-action-map
  [action-inserter metadata & args]
  (let [action (-> action-inserter meta :action)]
    (action-map
     action
     (or args [])
     metadata)))

(deftest action-dependencies-test
  (let [[bx b] (test-action-and-map {:always-before #{:id-f fx}
                                     :always-after #{:id-g gx}
                                     :action-id :id-b})]
    (is (= :id-b (:action-id b)))
    (is (= {{:action-id :id-b :action-symbol (action-symbol bx)}
            #{{:action-symbol 'g} {:action-symbol 'gg :action-id :id-g}}

            {:action-symbol 'ff :action-id :id-f}
            #{{:action-symbol (action-symbol bx) :action-id :id-b}}

            {:action-symbol 'f}
            #{{:action-symbol (action-symbol bx) :action-id :id-b}}}
           (#'action-plan/action-dependencies
            {:id-f 'ff :id-g 'gg}
            b)))))

(deftest action-scope-dependencies-test
  (let [action-f (test-action-map fx {:always-after #{gx} :action-id :id-f})
        action-g (test-action-map gx {})
        action-h (test-action-map
                  hx
                  {:always-after #{gx}
                   :always-before #{:id-f}
                   :action-id :id-h})
        actions [action-f action-g action-h]]
    (is (= :id-f (:action-id action-f)))
    (is (action-symbol (:action action-f)))
    (is (= [{:id-h 'h :id-f 'f}
            {{:action-symbol 'h :action-id :id-h} #{{:action-symbol 'g}}
             {:action-id :id-f :action-symbol 'f}
             #{{:action-symbol 'h :action-id :id-h} {:action-symbol 'g}}}
            {{:action-symbol 'h :action-id :id-h} #{action-h}
             {:action-symbol 'g} #{action-g}}
            {{:action-symbol 'h :action-id :id-h} #{action-g}
             {:action-id :id-f :action-symbol 'f} #{action-h action-g}}]
           (#'action-plan/action-scope-dependencies actions)))))

(deftest enforce-scope-dependencies-test
  (let [action-f (test-action-map
                  fx {:always-after #{gx}
                     :action-id :id-f})
        action-g (test-action-map gx {})
        action-h (test-action-map
                  hx {:always-after #{gx}
                     :always-before #{:id-f}})
        actions [action-f action-g action-h]]
    (is (= [action-g action-h action-f]
           (#'action-plan/enforce-scope-dependencies actions)))))

(deftest enforce-precedence-test
  (testing "reordering across execution-type"
    (let [f (fn [session & x] [(str x) session])
          fa (declare-action 'fa {:execution :aggregated})
          _ (implement-action* fa :default {} f)
          a1 (action-map (-> fa meta :action) [2] {})
          a2 (test-action-map gx {:always-before fa} 1)
          action-plan (-> nil
                          (add-action-map (assoc a1 :node-value-path :v1))
                          (add-action-map (assoc a2 :node-value-path :v2)))]
      (is (=
           [["[1]" "((2))"] {:node-values {:v1 "((2))" :v2 "[1]"}}]
           (->
            (execute
             (translate action-plan {})
             {}
             (executor :default)
             stop-execution-on-error)
            (dissoc-action-plan)))))))

(deftest delayed-argument-test
  (testing "delayed arguments"
    (let [g (make-action 'g :aggregated {})
          _ (add-action-implementation!
             g :default {} (fn [session & x] [(str x) session]))
          a (action-map
             g
             [(pallet.argument/delayed [session] 1)]
             {})
          action-plan (->
                       nil
                       (add-action-map (assoc a :node-value-path :v)))]
      (is (=
           [["((1))"] {:node-values {:v "((1))"}}]
           (->
            (execute
             (translate action-plan {})
             {}
             (executor :default)
             stop-execution-on-error)
            (dissoc-action-plan)))))))

(deftest translate-execution-test
  (is (= :aggregated (#'action-plan/translate-execution :aggregated)))
  (is (= :aggregated (#'action-plan/translate-execution :aggregated-crate-fn)))
  (is (= :in-sequence (#'action-plan/translate-execution :in-sequence)))
  (is (= :in-sequence (#'action-plan/translate-execution :delayed-crate-fn))))


(deftest execute-delayed-crate-fn-test
  (let [fa (declare-action 'fa {:execution :in-sequence})]
    (testing "delayed-crate-fn"
      (let [g (make-action 'g :delayed-crate-fn {})
            _ (add-action-implementation! g :default {} fa)]
        (testing "without context"
          (let [a (action-map g [1] {})
                action-plan (->
                             nil
                             (add-action-map (assoc a :node-value-path :v)))]
            (is (=
                 [{:action (-> fa meta :action) :args [1] :context nil}]
                 (map
                  #(dissoc % :node-value-path)
                  (translate action-plan {:target-id :id :phase :p}))))))
        (testing "with context"
          (let [a (with-phase-context {:kw :k :msg "m"}
                    (with-phase-context {:kw :k :msg "n"}
                      (action-map g [1] {})))
                action-plan (->
                             nil
                             (add-action-map (assoc a :node-value-path :v)))]
            (is (= [{:kw :k :msg "m"}]
                   (map #(dissoc % :ns :line) (:context a))))
            (is (=
                 [{:action (-> fa meta :action) :args [1] :context ["m"]}]
                 (map
                  #(dissoc % :node-value-path)
                  (translate action-plan {:target-id :id :phase :p}))))))))
    (testing "aggregated-crate-fn"
      (let [g (make-action 'g :aggregated-crate-fn {})
            _ (add-action-implementation! g :default {} fa)
            ha (make-action 'h :in-sequence {})
            h1 (action-map ha [1] {})
            h2 (action-map ha [2] {})]
        (testing "without context"
          (let [a (action-map g [1] {})
                action-plan (->
                             nil
                             (add-action-map (assoc h1 :node-value-path :h1))
                             (add-action-map (assoc a :node-value-path :v))
                             (add-action-map (assoc h2 :node-value-path :h2)))]
            (is (=
                 [{:action (-> fa meta :action) :args [[1]] :context nil}
                  {:action ha :args [1] :context nil}
                  {:action ha :args [2] :context nil}]
                 (map
                  #(dissoc % :node-value-path)
                  (translate action-plan {:target-id :id :phase :p}))))))
        (testing "with context"
          (let [a (with-phase-context {:kw :k :msg "m"}
                    (with-phase-context {:kw :k :msg "n"}
                      (action-map g [1] {})))
                action-plan (->
                             nil
                             (add-action-map (assoc h1 :node-value-path :h1))
                             (add-action-map (assoc a :node-value-path :v))
                             (add-action-map (assoc h2 :node-value-path :h2)))]
            (is (=
                 [{:action (-> fa meta :action) :args [[1]] :context ["m"]}
                  {:action ha :args [1] :context nil}
                  {:action ha :args [2] :context nil}]
                 (map
                  #(dissoc % :node-value-path)
                  (translate action-plan {:target-id :id :phase :p}))))))))))
