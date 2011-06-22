(ns pallet.action.exec-script-test
  (:use pallet.action.exec-script)
  (:use
   clojure.test
   pallet.build-actions)
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.script.lib :as lib]
   [pallet.test-utils :as test-utils]))

(use-fixtures :once test-utils/with-bash-script-language)

(deftest exec-script*-test
  (is (= "ls file1\n"
         (first (build-actions
                 {}
                 (exec-script* (stevedore/script (~lib/ls "file1"))))))))

(deftest exec-script-test
  (is (= "ls file1\n"
         (first (build-actions
                 {}
                 (exec-script (~lib/ls "file1"))))))
    (is (= "ls file1\nls file2\n"
           (first (build-actions
                   {}
                   (exec-script (~lib/ls "file1") (~lib/ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-actions
                 {}
                 (exec-checked-script "check" (~lib/ls "file1")))))))
