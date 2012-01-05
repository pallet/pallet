(ns pallet.action.exec-script-test
  (:use pallet.action.exec-script)
  (:use
   clojure.test
   pallet.build-actions
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.node-value :only [node-value]])
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.script.lib :as lib]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :once
 test-utils/with-bash-script-language
 (logging-threshold-fixture))

(deftest exec-script*-test
  (let [v (promise)
        rv (let-actions {}
             [nv (exec-script* (stevedore/script (~lib/ls "file1")))
              _ #(do (deliver v nv) [nv %])]
             nv)]
    (is (= "ls file1" (first rv)))
    (is (= "ls file1" (node-value @v (second rv))))))

(deftest exec-script-test
  (is (= "ls file1"
         (first (build-actions {}
                  (exec-script (~lib/ls "file1"))))))
  (is (= "ls file1\nls file2\n"
         (first (build-actions {}
                  (exec-script (~lib/ls "file1") (~lib/ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-actions {}
                  (exec-checked-script "check" (~lib/ls "file1")))))))
