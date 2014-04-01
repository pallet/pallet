(ns pallet.phase-test
  (:require
   [clojure.test :refer :all]
   [pallet.phase :refer :all]))

(deftest phase-args-test
  (is (= nil (phase-args :x)))
  (is (= [] (phase-args [:x])))
  (is (= [:a :b] (phase-args [:x :a :b]))))

(deftest phase-kw-test
  (is (= :x (phase-kw :x)))
  (is (= :x (phase-kw [:x])))
  (is (= :x (phase-kw [:x :a :b]))))

;; (deftest target-phase-test
;;   (let [f (fn [])]
;;     (is (= f (target-phase {:x f} :x)))
;;     (is (= f (target-phase {:x f} [:x])))
;;     (is (= f (target-phase {:x f} [:x :a :b])))))
