(ns pallet.resource.exec-script-test
  (:use pallet.resource.exec-script)
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]))

(deftest exec-script*-test
  (is (= "ls file1\n"
         (first (build-resources
                 []
                 (exec-script* (stevedore/script (ls "file1"))))))))

(deftest exec-script-test
  (is (= "ls file1\n"
         (first (build-resources
                 []
                 (exec-script (ls "file1"))))))
    (is (= "ls file1\nls file2\n"
           (first (build-resources
                   []
                   (exec-script (ls "file1") (ls "file2")))))))

(deftest exec-checked-script-test
  (is (= (stevedore/checked-commands
          "check"
          "ls file1\n")
         (first (build-resources
                 []
                 (exec-checked-script "check" (ls "file1")))))))
