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
  (is (= 3 (-> 1 (if-> false (+ 1) (+ 2))))))

(deftest if-not->test
  (is (= 3 (-> 1 (if-not-> true  (+ 1) (+ 2)))))
  (is (= 2 (-> 1 (if-not-> false (+ 1) (+ 2))))))

(deftest apply->test
  (is (= 7 (-> 1 (apply-> + [1 2 3])))))

(deftest apply-map->test
  (is (= {:a 1 :b 2} (-> :a (apply-map-> hash-map 1 {:b 2})))))
