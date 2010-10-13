(ns pallet.resource-test
  (:use pallet.resource)
  (:require
   [pallet.compute.jclouds :as jclouds]
   [pallet.parameter :as parameter]
   pallet.resource.test-resource
   [clojure.contrib.string :as string]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))


;; (deftest reset-resources-test
;;   (with-init-resources {:k :v}
;;     (reset-resources)
;;     (is (= {} *required-resources*))))

;; (deftest in-phase-test
;;   (in-phase :fred
;;     (is (= :fred *phase*))))

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

(defmacro is-phase
  [request phase]
  `(do
     (is (= ~phase (:phase ~request)))
     ~request))

(deftest execute-after-phase-test
  (is (= :fred
         (:phase
          (execute-after-phase
           {:phase :fred}
           (is-phase :after-fred))))))

(deftest execute-pre-phase-test
  (is (= :fred
         (:phase
          (execute-pre-phase
           {:phase :fred}
           (is-phase :pre-fred))))))

(defn identity-resource [request x] x)

(deftest invoke-resource-test
  (testing "invoke aggregated execution"
    (let [request (invoke-resource
                   {:phase :configure :target-id :id} identity :a :aggregated)]
      (is (= identity
             (-> request :invocations :configure :id :aggregated first :f)))
      (is (= :a
             (-> request :invocations :configure :id :aggregated first :args)))
      (is (= :remote
             (-> request :invocations :configure :id :aggregated first
                 :location)))))

  (testing "invoke collected execution"
    (let [request (invoke-resource
                   {:phase :configure :target-id :id} identity :a :collected)]
      (is (= identity
             (-> request :invocations :configure :id :collected first :f)))
      (is (= :a
             (-> request :invocations :configure :id :collected first :args)))
      (is (= :remote
             (-> request :invocations :configure :id :collected first
                 :location)))))

  (testing "invoke in-sequence execution"
    (let [request (invoke-resource
                   {:phase :configure :target-id :id} identity :b :in-sequence)]
      (is (= identity
             (-> request :invocations :configure :id :in-sequence first :f)))
      (is (= :b
             (-> request :invocations :configure :id :in-sequence first :args)))
      (is (= :remote
             (-> request :invocations :configure :id :in-sequence first
                 :location)))))
  (testing "invoke local execution"
    (let [request (invoke-resource
                   {:phase :configure :target-id :id}
                   identity :b :in-sequence :fn/clojure)]
      (is (= identity
             (-> request :invocations :configure :id :in-sequence first :f)))
      (is (= :b
             (-> request :invocations :configure :id :in-sequence first :args)))
      (is (= :local
             (-> request :invocations :configure :id :in-sequence first
                 :location))))))

(deftest group-by-function
  (is (= '({:f 1 :args ((0 1 2) [:a :b]) :other :a}
           {:f 3 :args ((\f \o \o)) :other :c}
           {:f 2 :args ((0 1 2) ["bar baz"]) :other :b})
         (#'pallet.resource/group-by-function
          [{:f 1 :args (range 3) :other :a}
           {:f 3 :args (seq "foo") :other :c}
           {:f 2 :args (range 3) :other :b}
           {:f 2 :args ["bar baz"] :other :b}
           {:f 1 :args [:a :b] :other :a}]))))

(deftest bound-invocations-test
  (letfn [(combiner [request args] (string/join "" (map #(apply str %) args)))]
    (testing "aggregation"

      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :aggregated)
                        (invoke-resource combiner [:b] :aggregated))
            fs (bound-invocations (-> request :invocations :configure :id))]
        (is (= :remote (:location (first fs))))
        (is (= ":a:b" ((:f (first fs)) {})))))
    (testing "collection"

      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :collected)
                        (invoke-resource combiner [:b] :collected))
            fs (bound-invocations (-> request :invocations :configure :id))]
        (is (= :remote (:location (first fs))))
        (is (= ":a:b" ((:f (first fs)) {})))))
    (testing "with-local-sequence"
      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :aggregated)
                        (invoke-resource identity-resource [:b] :in-sequence)
                        (invoke-resource
                         identity-resource [:c] :in-sequence :fn/clojure))
            fs (bound-invocations (-> request :invocations :configure :id))]
        (is (not (.contains "lazy" (str fs))))
        (is (= ":a" ((:f (first fs)) {})))
        (is (= :b ((:f (second fs)) {})))
        (is (= :c ((:f (last fs)) {})))
        (is (= :remote (:location (first fs))))
        (is (= :remote (:location (second fs))))
        (is (= :local (:location (last fs))))))
    (testing "aggregated parameters"
      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :aggregated)
                        (invoke-resource combiner ["b" :c] :aggregated))
            m (produce-phase request)]
        (is (seq? m))
        (is (= 1 (count m)) "phase, location should be aggregated")
        (is (map? (first m)))
        (is (= :remote (:location (first m))))
        (is (fn? (:f (first m))))
        (is (= {:location :remote
                :type :script/bash
                :cmds ":ab:c\n"
                :request {}}
               ((:f (first m)) {})))
        (let [fs (produce-phases [:configure] request)]
          (is (= ":ab:c\n" (first fs)))))
      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :aggregated)
                        (invoke-resource identity-resource ["x"]
                         :in-sequence)
                        (invoke-resource combiner ["b" :c] :aggregated))
            fs (produce-phases [:configure] request)]
        (is (= ":ab:c\nx\n" (first fs)))))
    (testing "delayed parameters"
      (let [request (-> {:phase :configure :target-id :id}
                        (invoke-resource combiner [:a] :aggregated)
                        (invoke-resource combiner [:b] :aggregated)
                        (invoke-resource combiner
                         [(pallet.parameter/lookup :p)] :aggregated))
            fs (produce-phases
                [:configure] (assoc request :parameters {:p "p"}))]
        (is (= ":a:bp\n" (first fs)))))))

;; (test-utils/with-private-vars [pallet.resource [with-request-arg]]
;;   (deftest with-request-arg-test
;;     (is (= `(pallet.argument/delayed [request] 1)
;;            (with-request-arg `request 1)))
;;     (is (= `(pallet.argument/delayed [request] (list request))
;;            (with-request-arg `request `(list request))))))

;; (deftest with-request-test
;;   (is (= `(f (pallet.argument/delayed [request] 1))
;;          (macroexpand-1 `(with-request [request] (f 1)))))
;;   (is (= `(f (pallet.argument/delayed [request] (list request)))
;;          (macroexpand-1 `(with-request [request] (f (list request)))))))

(defn test-combiner [request args]
  (string/join "\n" args))

(defn af [request arg-name])
(defaggregate test-resource1
  {:copy-arglist af}
  (f [request arg] (string/join "" (map (comp name first) arg))))

(deftest defresource-test
  (testing "resource"
    (defresource test-resource (f [request arg] (name arg)))
    (is (= "a\n" (first (build-resources
                         [:target-id :id]
                         (test-resource :a)))))
    (is (= '([request arg]) (:arglists (meta #'test-resource)))))
  (testing "aggregate"
    (defaggregate test-resource (f [request arg] (string/join "" arg)))

    (is (= [:a]
             (-> (test-resource {:phase :configure :target-id :id} :a)
                 :invocations :configure :id :aggregated first :args))))
  (testing "aggregate with :use-arglist"
    (defaggregate test-resource
      {:use-arglist [arg-name]}
      (f [request arg] (string/join "" (map (comp name first) arg))))
    (is (= "a\n" (first (build-resources [] (test-resource :a)))))
    (is (= '([arg-name]) (:arglists (meta #'test-resource)))))
  (testing "aggregate with :copy-arglist"
    (is (= '([request arg-name]) (:arglists (meta #'af))))
    (is (= "a\n" (first (build-resources [] (test-resource1 :a)))))
    (is (= '([request arg-name]) (:arglists (meta #'test-resource1)))))
  (testing "collect"
    (defcollect test-resource (f [request arg] arg))
    (is (= [:a]
             (-> (test-resource {:phase :configure :target-id :id} :a)
                 :invocations :configure :id :collected first :args))))
  (testing "local"
    (deflocal test-resource (f [request arg] arg))
    (is (= {:f #'f :args [:a] :location :local :type :fn/clojure}
           (-> (test-resource {:phase :configure :target-id :id} :a)
               :invocations :configure :id :in-sequence first)))))

(defresource test-component
  (test-component-fn
   [request arg & options]
   (str arg)))

(deflocal test-local-component
  (test-local-component-fn
   [request k v]
   (assoc-in request [:parameters k] v)))

;; (deftest resource-phases-test
;;   (let [m (resource-phases (test-component :a))
;;         v (first (-> m :configure :in-sequence))]
;;     (is (= [:a] (:args v)))
;;     (is (= :remote (:location v)))))

(defn kv-local-resource [request k v] (assoc request k v))

(deftest output-resources-test
  (testing "concatenate remote phases"
    (let [{:keys [location f type]}
          (first
           (output-resources
            {:in-sequence
             [{:f identity-resource :args ["abc"]
               :location :remote
               :type :script/bash}
              {:f identity-resource :args ["d"]
               :location :remote
               :type :script/bash}]}))]
      (is (= :remote location))
      (is (= :script/bash type))
      (is (= {:type :script/bash :location :remote
              :cmds "abc\nd\n" :request {}}
             (f {})))))
  (testing "split local/remote phases"
    (let [f (fn [request] (assoc request :ff 1))
          r (output-resources {:in-sequence
                               [{:f identity-resource :args ["abc"]
                                 :location :remote
                                 :type :script/bash}
                                {:f f :args []
                                 :location :local
                                 :type :fn/clojure}
                                {:f identity-resource :args ["d"]
                                 :location :remote
                                 :type :script/bash}]})]
      (let [{:keys [location type f]} (first r)]
        (is (= :remote location))
        (is (= {:type :script/bash :location :remote
                :cmds "abc\n" :request {}}
               (f {})))
        (is (= :script/bash type)))
      (let [{:keys [location type f]} (second r)]
        (is (= :local location))
        (is (= {:type :fn/clojure, :location :local, :request {:ff 1}}
               (f {})))
        (is (= :fn/clojure type)))
      (let [{:keys [location type f]} (last r)]
        (is (= :remote location))
        (is (= {:type :script/bash :location :remote :cmds "d\n" :request {}}
               (f {})))
        (is (= :script/bash type)))))

  (testing "sequence local phases"
    (is (= {:type :fn/clojure, :location :local, :request {:b 3, :a 2}}
           ((:f
             (first
              (output-resources {:in-sequence
                                 [{:f kv-local-resource :args [:a 2]
                                   :location :local
                                   :type :fn/clojure}
                                  {:f kv-local-resource :args [:b 3]
                                   :location :local
                                   :type :fn/clojure}]})))
            {})))))

(deftest execute-commands-test
  (testing "returning of script arguments"
    (let [request {:commands [{:location :remote :type :script/bash
                               :f (fn [request] {:cmds "abc"
                                                 :request request
                                                 :location :remote
                                                 :type :script/bash})}]}]
      (is (= [["abc"] request]
               (execute-commands request {:script/bash (fn [cmds] cmds)})))))
  (testing "updating of request"
    (let [request {:commands [{:location :local :type :fn/clojure
                               :f (fn [request]
                                    {:request {assoc request :fred 1}})}]}]
      (is (= [[] {assoc request :fred 1}]
               (execute-commands request {:script/bash (fn [cmds] cmds)}))))))

(deftest produce-phases-test
  (let [node (jclouds/make-node "tag")]
    (testing "node with phase"
      (let [result (produce-phases
                    [:a]
                    {:target-id :id
                     :invocations {:a
                                   {:id
                                    {:in-sequence
                                     [{:f (fn [request] "abc")
                                       :args []
                                       :location :remote :type :script/bash}
                                      {:f (fn [request] "d")
                                       :args []
                                       :location :remote :type :script/bash}]}}}
                     :fred 1})]
        (is (string? (first result)))
        (is (= "abc\nd\n" (first result)))
        (is (map? (second result)))
        (is (= 1 (:fred (second result))))))
    ;; TODO put this back
    ;; (testing "inline phase"
    ;;   (is (= ":a\n"
    ;;          (first (produce-phases
    ;;                  [(phase (test-component {} :a))]
    ;;                  {}
    ;;                  {:target-node node})))))
    ))

(deftest build-resources-test
  (let [[result request] (build-resources
                          [:parameters {:a 1}]
                          (test-component "a")
                          (test-local-component :b 2)
                          (test-component "b"))]
    (is (= "a\nb\n" result))
    (is (map? request) "reques not updated correctly")
    (is (= 1 (-> request :parameters :a)) "parameters not threaded properly")
    (is (= 2 (-> request :parameters :b)) "parameters not threaded properly")))

(deftest defcomponent-test
  (is (= ":a\n"
         (first (build-resources [] (test-component :a))))))

;; TODO put these back
;; (deftest defphases-test
;;   (let [p (defphases
;;             :pa [(test-component :a)]
;;             :pb [(test-component :b)])]
;;     (is (map? p))
;;     (is (= [:pa :pb] (keys p)))
;;     (is (= {:type :script/bash, :location :remote, :cmds ":a\n", :request {}}
;;            ((:f (first (output-resources :pa p))) {})))
;;     (is (= {:type :script/bash, :location :remote, :cmds ":b\n", :request {}}
;;            ((:f (first (output-resources :pb p))) {})))))

;; (deftest phase-test
;;   (let [p (phase (test-component :a))]
;;     (is (vector? p))
;;     (is (keyword? (first p)))
;;     (is (map? (second p)))
;;     (is (= [(first p)] (keys (second p))))
;;     (is (= {:type :script/bash, :location :remote, :cmds ":a\n", :request {}}
;;            ((:f (first (output-resources (first p) (second p)))) {})))))
