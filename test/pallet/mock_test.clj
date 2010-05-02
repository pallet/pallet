(ns pallet.mock-test
  (:use pallet.mock :reload-all)
  (:use clojure.test)
  (:require
   [clojure.contrib.condition :as condition]))

(deftest verify-expectations-test
  (is (thrown?
       clojure.contrib.condition.Condition
       (verify-expectations [(fn [] (condition/raise :error 1))])))
  (is (nil?
       (verify-expectations [(fn [] true)]))))

(deftest once-test
  (with-expectations
    (let [f (once 'v1 [] `((once 1)))]
      (is (thrown?
           clojure.contrib.condition.Condition
           ((first *expectations*))))
      (is (= 1 (f)))
      (is (nil? ((first *expectations*)))))
    (let [f (once 'v1 [x] `((once (inc x))))]
      (is (thrown?
           clojure.contrib.condition.Condition
           ((first *expectations*))))
      (is (= 1 (f 0)))
      (is (nil? ((first *expectations*)))))))

(deftest construct-mock-test
  (let [mock (construct-mock  `(v1 [] true))]
    (is (= `(fn [] true)))))

(deftest add-mock-test
  (is (= ['a.b.c `(fn [] true)] (add-mock [] `(a.b.c [] true)))))

(deftest construct-bindings-test
  (is (= [] (construct-bindings '[])))
  (is (= ['a.b.c `(fn [] true)] (construct-bindings `[(a.b.c [] true)])))
  (is (vector? (construct-bindings [`(a.b.c [] true)]))))


(def x)

(deftest expects-test
  (expects []
    (is (= [] *expectations*)))
  (expects [(x [] true)]
    (is (= [] *expectations*))
    (is (x)))
  (expects [(x [] false)]
    (is (= [] *expectations*))
    (is (not (x)))))

(deftest once-test
  (is (nil?
       (expects [(x [] (once true))]
         (is (= 1 (count *expectations*)))
         (is (x))))))
