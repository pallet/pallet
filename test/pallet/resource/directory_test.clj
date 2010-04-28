(ns pallet.resource.directory-test
  (:use [pallet.resource.directory] :reload-all)
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource])
  (:use
   clojure.test
   pallet.test-utils))

(deftest mkdir-test
  (is (= "mkdir -p dir"
         (stevedore/script (mkdir "dir" ~{:p true})))))


(deftest directory*-test
  (is (= (utils/cmd-checked "directory file1" "mkdir -p file1")
         (directory* "file1"))))

(deftest directory-test
  (is (= (utils/cmd-checked "directory file1" "mkdir -p file1")
         (resource/build-resources [] (directory "file1")))))
