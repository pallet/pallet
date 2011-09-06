(ns pallet.mock-test
  (:use pallet.mock)
  (:use clojure.test)
  (:require
   [slingshot.core :as slingshot]))

(deftest verify-expectations-test
  (is (thrown?
       slingshot.Stone
       (verify-expectations [(fn [] (slingshot/throw+ {:error 1}))])))
  (is (nil?
       (verify-expectations [(fn [] true)]))))

(deftest once-test
  (with-expectations
    (let [f (once 'v1 [] `((once 1)))]
      (is (thrown?
           slingshot.Stone
           ((first *expectations*))))
      (is (= 1 (f)))
      (is (nil? ((first *expectations*)))))
    (let [f (once 'v1 [x] `((once (inc x))))]
      (is (thrown?
           slingshot.Stone
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
