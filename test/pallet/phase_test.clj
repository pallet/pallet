(ns pallet.phase-test
  (:require
   [clojure.test :refer :all]
   [pallet.api :refer [plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.session :refer [session with-session]]
   [pallet.phase
    :refer [all-phases-for-phase
            post-phase-name
            pre-phase-name
            schedule-in-post-phase
            schedule-in-pre-phase]]
   [pallet.test-utils :refer [test-session]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest post-phase-name-test
  (is (= :pallet.phase/post-fred (post-phase-name :fred))))

(deftest pre-phase-name-test
  (is (= :pallet.phase/pre-fred (pre-phase-name :fred))))

(defn is-phase
  [phase]
  (fn [session]
    (is (= phase (:phase session)))
    [nil session]))

(deftest schedule-in-post-phase-test
  (with-session (test-session {:phase :fred})
    (let [f (plan-fn
              (schedule-in-post-phase
               (is-phase :pallet.phase/post-fred)))]
      (f)
      (is (= :fred (:phase (session)))))))

(deftest schedule-in-pre-phase-test
  (with-session (test-session {:phase :fred})
    (let [f (plan-fn
              (schedule-in-pre-phase
               (is-phase :pallet.phase/pre-fred)))]
      (f)
      (is (= :fred (:phase (session)))))))

(deftest all-phases-for-phase-test
  (testing "pre, post added"
    (is (= [:pallet.phase/pre-fred :fred :pallet.phase/post-fred]
             (all-phases-for-phase :fred)))))

;;; These are failing due to the side effects of the state-checking-t
;;; being bypassed by the (= lr expr) optimisation in monad-expr in
;;; c.algo.monads
;; (deftest plan-fn-test
;;   (letfn [(id [s] [nil s])]
;;     (is (thrown-with-msg?
;;           clojure.lang.ExceptionInfo #"in phase session"
;;           ((plan-fn id) 1)))
;;     (is (= [nil (test-session)] ((plan-fn id) (test-session))))
;;     (let [fgh (fn fgh [_] nil)]
;;       (is (thrown-with-msg?
;;             clojure.lang.ExceptionInfo #"Problem probably caused in"
;;             ((plan-fn fgh) (test-session)))))))
