(ns pallet.resource.directory-test
  (:use pallet.resource.directory)
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource])
  (:use
   clojure.test
   pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(deftest mkdir-test
  (is (= "mkdir -p dir"
         (stevedore/script (mkdir "dir" ~{:p true})))))


(deftest directory*-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (directory* {} "file1"))))

(deftest directory-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (first (resource/build-resources [] (directory "file1")))))
  (testing "delete"
    (is (= (stevedore/checked-script "Delete directory file1" "rm -r -f file1")
           (first (resource/build-resources
                   []
                   (directory "file1" :action :delete :recursive true)))))))

(deftest directories-test
  (is (= (str
          (stevedore/chain-commands
           (directory* {} "d1" :owner "o")
           (directory* {} "d2" :owner "o"))
          \newline)
         (first
          (resource/build-resources
           []
           (directories ["d1" "d2"] :owner "o"))))))
