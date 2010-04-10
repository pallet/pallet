(ns pallet.resource-test
  (:use [pallet.resource] :reload-all)
  (:require pallet.resource.test-resource
            [clojure.contrib.str-utils2 :as string])
  (:use clojure.test

        pallet.test-utils))

(def test-atom (atom []))

(deftest reset-resources-test
  (swap! required-resources conj [:f :v])
  (reset-resources)
  (is (= {} @required-resources)))

(deftest in-phase-test
  (in-phase :fred
    (is (= :fred *phase*))))

(deftest after-phase-test
  (is (= :after-fred (after-phase :fred))))

(deftest execute-after-phase-test
  (in-phase :fred
    (execute-after-phase
     (is (= :after-fred *phase*)))))

(deftest add-invocation-test
  (reset-resources)
  (is (= {:configure [[:a :b]]}
         (swap! required-resources add-invocation [:a :b])))
  (in-phase :fred
    (is (= {:configure [[:a :b]] :fred [[:c :d]]}
           (swap! required-resources add-invocation [:c :d])))))

(deftest invoke-resource-test
  (reset! test-atom [])
  (reset-resources)
  (invoke-resource test-atom identity :a)
  (is (= [:a] @test-atom))
  (is (= {:configure [[identity test-atom]]} @required-resources))

  (invoke-resource test-atom identity :b)
  (is (= [:a :b] @test-atom))
  (is (= {:configure [[identity test-atom][identity test-atom]]}
         @required-resources)))

(with-private-vars [pallet.resource [produce-resource-fn]]

  (deftest produce-resource-fn-test
    (reset! test-atom [])
    (swap! test-atom conj :a)
    (let [f (produce-resource-fn [identity test-atom])]
      (is (= [] @test-atom))
      (is (= [:a] (f))))))

(deftest configured-resources-test
  (reset! test-atom [])
  (reset-resources)
  (invoke-resource test-atom identity :a)
  (invoke-resource test-atom identity :b)
  (let [fs (configured-resources)]
    (is (not (.contains "lazy" (str fs))))
    (is (= [] @test-atom))
    (reset-resources)
    (is (= [:a :b] ((first (fs :configure)))))))

(defn test-combiner [args]
  (string/join "\n" args))


(deftest defresource-test
  (reset! test-atom [])
  (defresource test-resource test-atom identity [arg])
  (test-resource :a)
  (is (= [[:a]] @test-atom)))

(defn- test-component-fn [arg]
  (str arg))

(defcomponent test-component test-component-fn [arg & options])

(deftest defcomponent-test
  (test-component :a)
  (is (= ":a\n" (build-resources [] (test-component :a)))))

(deftest resource-phases-test
  (let [m (resource-phases (test-component :a))]))

(deftest output-resources-test
  (is (= "abc\nd\n"
         (output-resources :a {:a [(fn [] "abc") (fn [] "d")]})))
  (is (= nil
         (output-resources :b {:a [(fn [] "abc") (fn [] "d")]}))))

(deftest produce-phases-test
  (is (= "abc\nd\n"
         (produce-phases [:a] "tag" [] {:a [(fn [] "abc") (fn [] "d")]})))
  (is (= ":a\n"
         (produce-phases [(phase (test-component :a))] "tag" [] {}))))

(deftest build-resources-test
  (reset! test-atom [])
  (let [s (build-resources []
           (invoke-resource test-atom test-combiner "a")
           (invoke-resource test-atom test-combiner "b"))]
    (is (= [] @test-atom))
    (reset-resources)
    (is (= "a\nb\n" s))))

(deftest defphases-test
  (let [p (defphases
            :pa [(test-component :a)]
            :pb [(test-component :b)])]
    (is (map? p))
    (is (= [:pa :pb] (keys p)))
    (is (= ":a\n" (output-resources :pa p)))
    (is (= ":b\n" (output-resources :pb p)))))

(deftest phase-test
  (let [p (phase (test-component :a))]
    (is (vector? p))
    (is (keyword? (first p)))
    (is (map? (second p)))
    (is (= [(first p)] (keys (second p))))
    (is (= ":a\n" (output-resources (first p) (second p))))))
