(ns pallet.actions.direct.conditional-test
  (:require pallet.actions.direct.conditional)
  (:use
   clojure.test
   [pallet.api :only [group-spec lift plan-fn]]
   [pallet.build-actions :only [build-actions]]
   [pallet.actions :only [exec-script plan-when plan-when-not]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.algo.fsmop :only [complete? failed?]]
   [pallet.node-value :only [assign-node-value make-node-value node-value]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils
    :only [make-localhost-compute make-node test-username]]
   [pallet.utils :pnly [with-temporary tmpfile]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest when-test
  (is (= "c\n"
         (first (build-actions {}
                  (plan-when (= 1 1)
                    (exec-script "c")))))
      "true condition causes when block to run")
  (is (= "\n"
         (first (build-actions {}
                  (plan-when false
                    (exec-script "c")))))
      "non-true condition causes when block not to run")
  (let [nv (make-node-value 'nv)]
    (is (= "c\n"
           (first (build-actions {}
                    (assign-node-value nv true)
                    (plan-when @nv
                      (exec-script "c")))))
        "true node-value causes when block to run")
    (is (= "\n"
           (first (build-actions {}
                    (assign-node-value nv nil)
                    (plan-when @nv
                      (exec-script "c")))))
        "non-true node-value causes when block not to run")))

(deftest when-not-test
  (is (= "c\n"
         (first (build-actions {}
                  (plan-when-not false
                    (exec-script "c")))))
      "false condition causes if block to run")
  (is (= "\n"
         (first (build-actions {}
                  (plan-when-not true
                    (exec-script "c")))))
      "true condition causes if block not to run"))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

(deftest with-script-test
  (with-temporary [tmp (tmpfile)]
    (.delete tmp)
    (testing "plan-when"
      (testing "with true condition"
        (let [compute (make-localhost-compute :group-name "local")
              op (lift
                  (group-spec "local")
                  :phase (plan-fn
                           (exec-script (touch (quoted ~(.getPath tmp))))
                           (plan-when (script (file-exists? ~(.getPath tmp)))
                             (exec-script (println "tmp found"))))
                  :compute compute
                  :user (local-test-user))
              session @op]
          (is (not (failed? op)))
          (is (= "tmp found\n"
                 (->> session :results (mapcat :result) last :out)))))
      (testing "with false condition"
        (.delete tmp)
        (let [compute (make-localhost-compute :group-name "local")
              op (lift
                  (group-spec "local")
                  :phase (plan-fn
                           (plan-when (script (file-exists? ~(.getPath tmp)))
                             (exec-script (println "tmp found"))))
                  :compute compute
                  :user (local-test-user))
              session @op]
          (is (not (failed? op)))
          (is (nil? (->> session :results (mapcat :result) last :out))))))
    (testing "plan-when-not"
      (testing "with true condition"
        (let [compute (make-localhost-compute :group-name "local")
              op (lift
                  (group-spec "local")
                  :phase (plan-fn
                           (exec-script (touch (quoted ~(.getPath tmp))))
                           (plan-when-not
                               (script (file-exists? ~(.getPath tmp)))
                             (exec-script (println "tmp not found"))))
                  :compute compute
                  :user (local-test-user))
              session @op]
          (is (not (failed? op)))
          (is (nil? (->> session :results (mapcat :result) last :out)))))
      (testing "with false condition"
        (.delete tmp)
        (let [compute (make-localhost-compute :group-name "local")
              op (lift
                  (group-spec "local")
                  :phase (plan-fn
                           (plan-when-not
                               (script (file-exists? ~(.getPath tmp)))
                             (exec-script (println "tmp not found"))))
                  :compute compute
                  :user (local-test-user))
              session @op]
          (is (not (failed? op)))
          (is (= "tmp not found\n"
                 (->> session :results (mapcat :result) last :out))))))))
