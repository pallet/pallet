(ns pallet.action.conditional-test
  (:refer-clojure :exclude [when when-not])
  (:use pallet.action.conditional)
  (:require
   [pallet.action :as action])
  (:use
   clojure.test
   pallet.build-actions
   pallet.test-utils
   [pallet.action.exec-script :only [exec-script]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.node-value :only [assign-node-value make-node-value node-value]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest when-test
  (is (= "c"
         (first (build-actions {}
                  (pipeline-when (= 1 1)
                    (exec-script "c")))))
      "true condition causes when block to run")
  (is (= ""
         (first (build-actions {}
                  (pipeline-when false
                    (exec-script "c")))))
      "non-true condition causes when block not to run")
  (let [nv (make-node-value 'nv)]
    (is (= "c"
           (first (build-actions {}
                    (assign-node-value nv true)
                    (pipeline-when nv
                      (exec-script "c")))))
        "true node-value causes when block to run")
    (is (= ""  ;; failing due to when being clobbered in p.m
           (first (build-actions {}
                    (assign-node-value nv nil)
                    (pipeline-when nv
                      (exec-script "c")))))
        "non-true node-value causes when block not to run")))

(deftest when-not-test
  (is (= "c"
         (first (build-actions {}
                  (when-not false
                    (exec-script "c")))))
      "false condition causes if block to run")
  (is (= ""
         (first (build-actions {}
                  (when-not true
                    (exec-script "c")))))
      "true condition causes if block not to run"))
