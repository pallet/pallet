(ns pallet.phase-test
  (:use pallet.phase)
  (:use
   clojure.test
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.test-utils :only [test-session]]))

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
  (let [session-in (test-session {:phase :fred})
        [_ session] ((schedule-in-post-phase
                            (is-phase :pallet.phase/post-fred))
                          session-in)]
    (is (= :fred (:phase session)))))

(deftest schedule-in-pre-phase-test
  (let [session-in (test-session {:phase :fred})
        [_ session] ((schedule-in-pre-phase
                       (is-phase :pallet.phase/pre-fred))
                     session-in)]
    (is (= :fred (:phase session)))))

(deftest all-phases-for-phase-test
  (testing "pre, post added"
    (is (= [:pallet.phase/pre-fred :fred :pallet.phase/post-fred]
             (all-phases-for-phase :fred)))))

;;; These are failing due to the side effects of the state-checking-t
;;; being bypassed by the (= lr expr) optimisation in monad-expr in
;;; c.algo.monads
;; (deftest phase-fn-test
;;   (letfn [(id [s] [nil s])]
;;     (is (thrown-with-msg?
;;           slingshot.ExceptionInfo #"in phase session"
;;           ((phase-fn id) 1)))
;;     (is (= [nil (test-session)] ((phase-fn id) (test-session))))
;;     (let [fgh (fn fgh [_] nil)]
;;       (is (thrown-with-msg?
;;             slingshot.ExceptionInfo #"Problem probably caused in"
;;             ((phase-fn fgh) (test-session)))))))
