(ns pallet.parameter-test
  (:use pallet.parameter :reload-all)
  (:use
   clojure.test
   pallet.test-utils))

(defn reset-default-parameters
  [f]
  (reset! default-parameters {})
  (f))

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

(deftest update-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (is (= {:a 1 :b 2} *parameters*))
    (update [:b] 3)
    (is (= {:a 1 :b 3} *parameters*))
    (update [:c] 4)
    (is (= {:a 1 :b 3 :c 4} *parameters*))))

(deftest evaluate-test
  (is (= "xx" (evaluate "xx"))))

(deftest lookup-test
  (default :a 1 :b 2)
  (with-parameters [:default]
    (let [l (lookup :b)]
      (is (= 2 (evaluate l)))))
  (let [kf (fn [] [:default])]
    (with-parameters (kf)
      (let [l (lookup :b)]
        (is (= 2 (evaluate l)))))))

