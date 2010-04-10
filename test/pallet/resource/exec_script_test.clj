(ns pallet.resource.exec-script-test
  (:use [pallet.resource.exec-script] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(deftest exec-script-test
  (is (= "ls file1\n"
         (build-resources [] (exec-script (script (ls "file1")))))))
