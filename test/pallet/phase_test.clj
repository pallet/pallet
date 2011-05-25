(ns pallet.phase-test
  (:use pallet.phase)
  (:use
   clojure.test))


(deftest post-phase-name-test
  (is (= :pallet.phase/post-fred (post-phase-name :fred))))

(deftest pre-phase-name-test
  (is (= :pallet.phase/pre-fred (pre-phase-name :fred))))

(defmacro is-phase
  [session phase]
  `(do
     (is (= ~phase (:phase ~session)))
     ~session))

(deftest schedule-in-post-phase-test
  (is (= :fred
         (:phase
          (schedule-in-post-phase
           {:phase :fred}
           (is-phase :pallet.phase/post-fred))))))

(deftest schedule-in-pre-phase-test
  (is (= :fred
         (:phase
          (schedule-in-pre-phase
           {:phase :fred}
           (is-phase :pallet.phase/pre-fred))))))

(deftest all-phases-for-phase-test
  (testing "pre, post added"
    (is (= [:pallet.phase/pre-fred :fred :pallet.phase/post-fred]
             (all-phases-for-phase :fred)))))
