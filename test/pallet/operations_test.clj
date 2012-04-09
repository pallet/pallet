(ns pallet.operations-test
  (:use
   clojure.test
   pallet.operations))

(defn op-f [] :op)
(defn op-g [] :op)

(deftest operation-test
  (testing "no steps"
    (is (= {:op-name 'op :args '[arg] :steps []}
           (operation op [arg]))))
  (testing "one step with result"
    (is (= {:op-name 'op :args '[arg]
            :steps [{:result-sym 'x :op-sym 'op-f :args []}]}
           (operation op [arg]
             [x (op-f)]))))
  (testing "two steps with result"
    (is (= {:op-name 'op :args '[arg]
            :steps [{:result-sym 'x :op-sym 'op-f :args []}
                    {:result-sym 'y :op-sym 'op-g :args[]}]}
           (operation op [arg]
             [x (op-f)]
             [y (op-g)]))))
  (testing "one step with no result"
    (let [result (operation op [arg] (op-f))]
      (is (= {:op-name 'op :args '[arg]} (dissoc result :steps)))
      (is (= {:op-sym 'op-f :args []}
             (-> (:steps result) first (dissoc :result-sym))))
      (is (symbol? (-> (:steps result) first :result-sym)))))
  (testing "one step with result and arg"
    (is (= {:op-name 'op :args '[arg]
            :steps [{:result-sym 'x :op-sym 'op-f :args '[arg]}]}
           (operation op [arg]
             [x (op-f arg)])))))

(deftest operations-test
  (is (= {'op {:op-name 'op :args '[arg] :steps []}}
         (operations (operation op [arg])))))
