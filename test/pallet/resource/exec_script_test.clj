(ns pallet.resource.exec-script-test
  (:use pallet.resource.exec-script)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest exec-script-test
  (is (= "ls file1\n"
         (build-resources [] (exec-script (script (ls "file1")))))))
