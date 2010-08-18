(ns pallet.resource-test
  (:use pallet.resource)
  (:require
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   pallet.resource.test-resource
   [clojure.contrib.string :as string])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest reset-resources-test
  (with-init-resources {:k :v}
    (reset-resources)
    (is (= {} *required-resources*))))

(deftest in-phase-test
  (in-phase :fred
    (is (= :fred *phase*))))

(deftest after-phase-test
  (is (= :after-fred (after-phase :fred))))

(deftest pre-phase-test
  (is (= :pre-fred (pre-phase :fred))))

(deftest phase-list-test
  (testing "pre, after added"
    (is (= [:pre-fred :fred :after-fred]
             (phase-list [:fred]))))
  (testing "configure as default"
    (is (= [:pre-configure :configure :after-configure]
             (phase-list [])))))

(deftest execute-after-phase-test
  (in-phase :fred
    (execute-after-phase
     (is (= :after-fred *phase*)))))

(deftest execute-pre-phase-test
  (in-phase :fred
    (execute-pre-phase
     (is (= :pre-fred *phase*)))))

(deftest invoke-resource-test
  (with-init-resources nil
    (invoke-resource identity :a :aggregated)
    (is (= identity (-> *required-resources* :configure :aggregated ffirst)))
    (is (= :a (-> *required-resources* :configure :aggregated first second)))
    (is (= :remote (-> *required-resources* :configure :aggregated first last)))

    (invoke-resource identity :b :in-sequence)
    (is (= identity (-> *required-resources* :configure :in-sequence ffirst)))
    (is (= :b (-> *required-resources* :configure :in-sequence first second)))
    (is (= :remote
           (-> *required-resources* :configure :in-sequence first last))))

  (with-init-resources nil
    (invoke-resource identity :b :local-in-sequence)
    (is (= identity (-> *required-resources* :configure :in-sequence ffirst)))
    (is (= :b (-> *required-resources* :configure :in-sequence first second)))
    (is (= :local
           (-> *required-resources* :configure :in-sequence first last)))))

(deftest group-pairs-by-key-test
  (is (= '([1
            ((0 1 2)
             [:a :b])]
           [3 ((\f \o \o))]
             [2
              ((0 1 2)
               ["bar baz"])])
         (#'pallet.resource/group-pairs-by-key
          [[1 (range 3)] [3 (seq "foo")] [2 (range 3)] [2 ["bar baz"]]
           [1 [:a :b]]]))))

(with-private-vars [pallet.resource [execution-ordering]]
  (deftest execution-ordering-test
    (is (= '([:aggregated 1] [:in-sequence 2 :remote] [:in-sequence 3 :local])
           (sort-by
            (comp execution-ordering first)
            [[:in-sequence 2 :remote]
             [:aggregated 1]
             [:in-sequence 3 :local]])))))

(deftest configured-resources-test
  (letfn [(combiner [args] (string/join "" (map #(apply str %) args)))]
    (testing "aggregation"
      (with-init-resources nil
        (invoke-resource combiner [:a] :aggregated)
        (invoke-resource combiner [:b] :aggregated)
        (let [fs (configured-resources *required-resources*)]
          (is (= :remote (first (first (fs :configure)))))
          (is (= ":a:b" ((second (first (fs :configure)))))))))
    (testing "with-local-sequence"
      (with-init-resources nil
        (invoke-resource combiner [:a] :aggregated)
        (invoke-resource identity [:b] :in-sequence)
        (invoke-resource identity [:c] :local-in-sequence)
        (let [fs (configured-resources *required-resources*)]
          (is (not (.contains "lazy" (str fs))))
          (is (= ":a" ((second (first (fs :configure))))))
          (is (= :b ((second (second (fs :configure))))))
          (is (= :c ((second (last (fs :configure))))))
          (is (= :remote (first (first (fs :configure)))))
          (is (= :remote (first (second (fs :configure)))))
          (is (= :local (first (last (fs :configure))))))))
    (testing "aggregated parameters"
      (with-init-resources nil
        (invoke-resource combiner [:a] :aggregated)
        (invoke-resource combiner ["b" :c] :aggregated)
        (let [fs (produce-phases [:configure] *required-resources*)]
          (is (= ":ab:c\n" (ffirst fs)))))
      (with-init-resources nil
        (invoke-resource combiner [:a] :aggregated)
        (invoke-resource identity ["x"] :in-sequence)
        (invoke-resource combiner ["b" :c] :aggregated)
        (let [fs (produce-phases [:configure] *required-resources*)]
          (is (= ":ab:c\nx\n" (ffirst fs))))))
    (testing "delayed parameters"
      (with-init-resources nil
        (invoke-resource combiner [:a] :aggregated)
        (invoke-resource combiner [:b] :aggregated)
        (invoke-resource combiner [(pallet.parameter/lookup :p)] :aggregated)
        (binding [pallet.parameter/*parameters* {:p "p"}]
          (let [fs (produce-phases [:configure] *required-resources*)]
            (is (= ":a:bp\n" (ffirst fs)))))))))

(defn test-combiner [args]
  (string/join "\n" args))

(deftest defresource-test
  (with-init-resources nil
    (defaggregate test-resource identity [arg])
    (test-resource :a)
    (is (= [:a] (-> *required-resources* :configure :aggregated first second))))
  (with-init-resources nil
    (deflocal test-resource identity [arg])
    (test-resource :a)
    (is (= [#'identity [:a] :local]
             (-> *required-resources* :configure :in-sequence first)))))

(defn- test-component-fn [arg]
  (str arg))

(defresource test-component test-component-fn [arg & options])

(deftest resource-phases-test
  (with-init-resources nil
    (let [m (resource-phases (test-component :a))
          v (first (-> m :configure :in-sequence))]
      (is (= [:a] (second v)))
      (is (= :remote (last v))))))

(deftest defcomponent-test
  (with-init-resources nil
    (is (= ":a\n" (build-resources [] (test-component :a))))))


(deftest output-resources-test
  (with-init-resources nil
    (testing "concatenate remote phases"
      (is (= ["abc\nd\n"]
               (output-resources :a {:a {:in-sequence
                                         [[identity ["abc"] :remote]
                                          [identity ["d"]  :remote]]}}))))
    (testing "split local/remote phases"
      (let [f (fn [])
            r (output-resources :a {:a {:in-sequence
                                           [[identity ["abc"] :remote]
                                            [f [] :local]
                                            [identity ["d"] :remote]]}})]
        (is (= "abc\n" (first r)))
        (is (= nil  ((first (second r)))))
        (is (= "d\n" (last r)))))

    (testing "sequence local phases"
      (let [f identity g identity]
        (is (= [2 3]
                 (map #(%) (first (output-resources :a {:a {:in-sequence
                                                   [[f [2] :local]
                                                    [g [3] :local]]}})))))))
    (testing "Undefined phase"
      (is (= '()
             (output-resources :b {:a {:in-sequence
                                       [[identity ["abc"] :remote]
                                        [identity ["d"] :remote]]}}))))))

(deftest produce-phases-test
  (let [node (compute/make-node "tag")]
    (with-init-resources nil
      (with-target [node {}]
        (testing "node with phase"
          (is (= [["abc\nd\n"]]
                   (produce-phases
                    [:a]
                    {:a
                     {:in-sequence
                      [[(fn [] "abc") [] :remote]
                       [(fn [] "d") [] :remote]]}}))))
        (testing "inline phase"
          (is (= [[":a\n"]]
                   (produce-phases [(phase (test-component :a))] {}))))))))

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
      (is (= [":a\n"] (output-resources :pa p)))
      (is (= [":b\n"] (output-resources :pb p))))))

(deftest phase-test
  (with-init-resources nil
    (let [p (phase (test-component :a))]
      (is (vector? p))
      (is (keyword? (first p)))
      (is (map? (second p)))
      (is (= [(first p)] (keys (second p))))
      (is (= [":a\n"] (output-resources (first p) (second p)))))))


(defn lookup-test-fn
  [a] (str a))

(defresource lookup-test-resource
  lookup-test-fn [a])

(deftest lookup-test
  (is (= "9\n"
         (test-resource-build
          [nil {} :a 1 :b 9]
          (lookup-test-resource
           (parameter/lookup :b))))))

(deftest set-parameters-test
  (test-resource-build
   [nil {:image [:ubuntu]}]
   (parameters [:a] 33)
   (pallet.test-utils/parameters-test [:a] 33)))
