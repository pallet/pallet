(ns pallet.resource.exec-script-test
  (:use pallet.resource.exec-script)
  (:use
   clojure.test
   pallet.build-actions
   pallet.test-utils)
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.file :as file]))

(deftest exec-script*-test
  (is (= "ls file1\n"
         (first (build-actions
                 {}
                 (exec-script* (stevedore/script (file/ls "file1"))))))))

(deftest exec-script-test
  (is (= "ls file1\n"
         (first (build-actions
                 {}
                 (exec-script (file/ls "file1"))))))
    (is (= "ls file1\nls file2\n"
           (first (build-actions
                   {}
                   (exec-script (file/ls "file1") (file/ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-actions
                 {}
                 (exec-checked-script "check" (file/ls "file1")))))))
