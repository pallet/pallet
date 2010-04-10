(ns pallet.resource.directory-test
  (:use [pallet.resource.directory] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(deftest mkdir-test
  (is (= "mkdir -p dir"
         (script (mkdir "dir" ~{:p true})))))


(deftest directory-test
  (is (= "mkdir -p file1\n"
         (build-resources [] (directory "file1")))))
