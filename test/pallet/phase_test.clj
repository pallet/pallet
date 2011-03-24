(ns pallet.phase-test
  (:use pallet.phase)
  (:use
   clojure.test))


(defmacro is-phase
  [request phase]
  `(do
     (is (= ~phase (:phase ~request)))
     ~request))

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
