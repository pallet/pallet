(ns pallet.parameter-test
  (:refer-clojure :exclude [assoc!])
  (:use pallet.parameter)
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   pallet.arguments))

(use-fixtures :each reset-default-parameters)

(deftest default-test
  (default :a 1 :b 2)
  (is (= {:default {:a 1 :b 2}} @default-parameters))
  (default :c 2)
  (is (= {:default {:a 1 :b 2 :c 2}} @default-parameters)))

(deftest default-for-test
  (default-for :os :a 1 :b 2)
  (is (= {:os {:a 1 :b 2}} @default-parameters)))


(deftest with-parameters-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (is (= {:a 1 :b 2} *parameters*)))
  (default-for :os :b 3)
  (with-parameters [:default :os]
    (is (= {:a 1 :b 3} *parameters*))))

(deftest assoc!-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (is (= {:a 1 :b 2} *parameters*))
    (assoc! [:b] 3)
    (is (= {:a 1 :b 3} *parameters*))
    (assoc! [:c] 4)
    (is (= {:a 1 :b 3 :c 4} *parameters*))))

(deftest update!-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (is (= {:a 1 :b 2} *parameters*))
    (update! [:b] (fn [x] (+ x 3)))
    (is (= {:a 1 :b 5} *parameters*))
    (update! [:c] (fn [_] 4))
    (is (= {:a 1 :b 5 :c 4} *parameters*))))

(deftest lookup-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (let [l (lookup :b)]
      (is (= 2 (pallet.arguments/evaluate l)))))
  (let [kf (fn [] [:default])]
    (with-parameters (kf)
      (let [l (lookup :b)]
        (is (= 2 (pallet.arguments/evaluate l)))))))
