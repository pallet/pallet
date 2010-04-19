(ns pallet.resource-test
  (:use pallet.resource :reload-all)
  (:require pallet.resource.test-resource
    pallet.compat)
  (:use clojure.test
        pallet.test-utils))

(pallet.compat/require-contrib)

(deftest reset-resources-test
  (with-init-resources {:k :v}
    (reset-resources)
    (is (= {} *required-resources*))))

(deftest in-phase-test
  (in-phase :fred
    (is (= :fred *phase*))))

(deftest after-phase-test
  (is (= :after-fred (after-phase :fred))))

(deftest execute-after-phase-test
  (in-phase :fred
    (execute-after-phase
     (is (= :after-fred *phase*)))))

(deftest invoke-resource-test
  (with-init-resources nil
    (invoke-resource identity :a :aggregated)
    (is (= identity (-> *required-resources* :configure :aggregated ffirst)))
    (is (= :a (-> *required-resources* :configure :aggregated first second)))

    (invoke-resource identity :b :in-sequence)
    (is (= identity (-> *required-resources* :configure :in-sequence ffirst)))
    (is (= :b (-> *required-resources* :configure :in-sequence first second)))))

(deftest group-pairs-by-key-test
  (is (= '([1
            ((0 1 2)
              [:a :b])]
           [3 ((\f \o \o))]
           [2
            ((0 1 2)
              ["bar baz"])])
        (#'pallet.resource/group-pairs-by-key
          [[1 (range 3)] [3 (seq "foo")] [2 (range 3)] [2 ["bar baz"]] [1 [:a :b]]]))))

(deftest configured-resources-test
  (with-init-resources nil
    (invoke-resource identity :a :aggregated)
    (invoke-resource identity :b :aggregated)
    (let [fs (configured-resources)]
      (is (not (.contains "lazy" (str fs))))
      (is (= [:a :b] ((first (fs :configure))))))))

(defn test-combiner [args]
  (string/join "\n" args))

(deftest defresource-test
  (with-init-resources nil
    (defaggregate test-resource identity [arg])
    (test-resource :a)
    (is (= [:a] (-> *required-resources* :configure :aggregated first second)))))

(defn- test-component-fn [arg]
  (str arg))

(defresource test-component test-component-fn [arg & options])

(deftest defcomponent-test
  (with-init-resources nil
    (is (= ":a\n" (build-resources [] (test-component :a))))))

(deftest resource-phases-test
  (with-init-resources nil
    (let [m (resource-phases (test-component :a))])))

(deftest output-resources-test
  (with-init-resources nil
    (is (= "abc\nd\n"
          (output-resources :a {:a [(fn [] "abc") (fn [] "d")]})))
    (is (= nil
          (output-resources :b {:a [(fn [] "abc") (fn [] "d")]})))))

(deftest produce-phases-test
  (with-init-resources nil
    (is (= "abc\nd\n"
          (produce-phases [:a] "tag" [] {:a [(fn [] "abc") (fn [] "d")]})))
    (is (= ":a\n"
          (produce-phases [(phase (test-component :a))] "tag" [] {})))))

(deftest build-resources-test
  (with-init-resources nil
    (let [s (build-resources []
              (invoke-resource test-combiner ["a"])
              (invoke-resource test-combiner ["b"]))]
      (is (= "a\nb\n" s)))))

(deftest defphases-test
  (with-init-resources nil
    (let [p (defphases
              :pa [(test-component :a)]
              :pb [(test-component :b)])]
      (is (map? p))
      (is (= [:pa :pb] (keys p)))
      (is (= ":a\n" (output-resources :pa p)))
      (is (= ":b\n" (output-resources :pb p))))))

(deftest phase-test
  (with-init-resources nil
    (let [p (phase (test-component :a))]
      (is (vector? p))
      (is (keyword? (first p)))
      (is (map? (second p)))
      (is (= [(first p)] (keys (second p))))
      (is (= ":a\n" (output-resources (first p) (second p)))))))
