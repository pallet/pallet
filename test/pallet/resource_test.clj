(ns pallet.resource-test
  (:use [pallet.resource] :reload-all)
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
  (invoke-resource test-atom identity :a)
  (invoke-resource test-atom identity :b)
  (let [fs (configured-resources)]
    (is (= [] @test-atom))
    (reset-resources)
    (is (= [:a :b] ((first fs))))))

(deftest build-resources-test
  (reset! test-atom [])
  (let [f (build-resources
           (invoke-resource test-atom identity "a")
           (invoke-resource test-atom identity "b"))]
    (is (= [] @test-atom))
    (reset-resources)
    (is (= "[\"a\" \"b\"]" (f)))))

(deftest defresource-test
  (reset! test-atom [])
  (defresource test-resource test-atom identity [arg])
  (test-resource :a)
  (is (= [[:a]] @test-atom)))



