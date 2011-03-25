(ns pallet.phase-test
  (:use pallet.phase)
  (:use
   clojure.test))


(deftest post-phase-name-test
  (is (= :after-fred (post-phase-name :fred))))

(deftest pre-phase-name-test
  (is (= :pre-fred (pre-phase-name :fred))))

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
           (is-phase :after-fred))))))

(deftest schedule-in-pre-phase-test
  (is (= :fred
         (:phase
          (schedule-in-pre-phase
           {:phase :fred}
           (is-phase :pre-fred))))))
