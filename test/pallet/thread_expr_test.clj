(ns pallet.thread-expr-test
  (:use
   pallet.thread-expr
   clojure.test))

(deftest for->-test
  (is (= 7 (-> 1 (for-> [x [1 2 3]] (+ x))))))

(deftest when->test
  (is (= 2 (-> 1 (when-> true (+ 1)))))
  (is (= 1 (-> 1 (when-> false (+ 1))))))

(deftest when-not->test
  (is (= 1 (-> 1 (when-not-> true (+ 1)))))
  (is (= 2 (-> 1 (when-not-> false (+ 1))))))

(deftest when-let->test
  (is (= 2) (-> 1 (when-let-> [a 1] (+ a))))
  (is (= 1) (-> 1 (when-let-> [a nil] (+ a)))))

(deftest if->test
  (is (= 2 (-> 1 (if-> true  (+ 1) (+ 2)))))
  (is (= 3 (-> 1 (if-> false (+ 1) (+ 2)))))
  (is (= 1 (-> 1 (if-> false (+ 1))))))

(deftest if-not->test
  (is (= 3 (-> 1 (if-not-> true  (+ 1) (+ 2)))))
  (is (= 2 (-> 1 (if-not-> false (+ 1) (+ 2))))))

(deftest let->test
  (is (= 4 (-> 1 (let-> [a 1
                         b (inc a)]
                        (+ a b))))))

(deftest let-with-arg->test
  (is (= 5 (-> 1 (let-with-arg-> arg [a 1
                                      b (inc a)]
                   (+ a b arg))))))

(deftest expose-arg->test
  (is (= 2 (-> 1 (expose-arg-> [arg] (+ arg))))))

(deftest apply->test
  (is (= 7 (-> 1 (apply-> + [1 2 3])))))

(deftest apply-map->test
  (is (= {:a 1 :b 2} (-> :a (apply-map-> hash-map 1 {:b 2})))))

(deftest -->test
  (is (= 32 (--> 5 (for [a (range 3)
                         :let [x 5]]
                     (let [y 3]
                       (+ a x y)))))))
