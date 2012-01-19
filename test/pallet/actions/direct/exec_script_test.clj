(ns pallet.actions.direct.exec-script-test
  (:use
   clojure.test
   [pallet.build-actions :only [build-actions let-actions]]
   [pallet.actions :only [exec-script* exec-script exec-checked-script]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.script.lib :only [ls]]
   [pallet.node-value :only [node-value]]
   [pallet.test-utils :only [with-bash-script-language]])
  (:require
   pallet.actions.direct.exec-script
   [pallet.stevedore :as stevedore]))

(use-fixtures
 :once
 with-bash-script-language
 (logging-threshold-fixture))

(deftest exec-script*-test
  (let [v (promise)
        rv (let-actions {}
             [nv (exec-script* "ls file1")
              _ #(do (deliver v nv) [nv %])]
             nv)]
    (is (= "ls file1" (first rv)))
    (is (= "ls file1" (node-value @v (second rv))))))

(deftest exec-script-test
  (is (= "ls file1"
         (first (build-actions {}
                  (exec-script (~ls "file1"))))))
  (is (= "ls file1\nls file2\n"
         (first (build-actions {}
                  (exec-script (~ls "file1") (~ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-actions {}
                  (exec-checked-script "check" (~ls "file1"))))))
  (testing "with context"
    (is (= (stevedore/checked-commands
            "context\ncheck"
            "ls file1\n")
           (first
            (build-actions {:phase-context "context"}
              (exec-checked-script "check" (~ls "file1"))))))))
