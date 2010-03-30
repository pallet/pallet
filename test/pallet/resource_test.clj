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
  (is (= []
         @required-resources)))

(deftest invoke-resource-test
  (reset! test-atom [])
  (reset-resources)
  (invoke-resource test-atom identity :a)
  (is (= [:a] @test-atom))
  (is (= [[identity test-atom]] @required-resources))

  (invoke-resource test-atom identity :b)
  (is (= [:a :b] @test-atom))
  (is (= [[identity test-atom][identity test-atom]] @required-resources)))

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
    (is (= [:a :b] ((first fs))))))

(defn test-combiner [args]
  (string/join "\n" args))

(deftest build-resources-test
  (reset! test-atom [])
  (let [s (build-resources
           (invoke-resource test-atom test-combiner "a")
           (invoke-resource test-atom test-combiner "b"))]
    (is (= [] @test-atom))
    (reset-resources)
    (is (= "a\nb\n" s))))

(deftest build-resource-fn-test
  (reset! test-atom [])
  (let [f (build-resource-fn
           (invoke-resource test-atom test-combiner "a")
           (invoke-resource test-atom test-combiner "b"))]
    (is (= [] @test-atom))
    (reset-resources)
    (is (= "a\nb\n" (f)))))

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
  (is (= ":a\n"
         (build-resources (test-component :a)))))

(deftest bootstrap-resources-test
  (reset! required-resources [:a])
  (let [f (bootstrap-resources
           (is (= [] @required-resources))
           (pallet.resource.test-resource/test-resource))]
    (is (map? f))
    (is (:bootstrap-script f))
    (is (ifn? (:bootstrap-script f)))
    (is (= "test-resource::tag[:ubuntu]\n"
           ((:bootstrap-script f) :tag [:ubuntu])))))
