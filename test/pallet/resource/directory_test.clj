(ns pallet.resource.directory-test
  (:use pallet.resource.directory)
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test
   pallet.build-actions))

(use-fixtures :once test-utils/with-ubuntu-script-template)

(def directory* (action/action-fn directory))

(deftest mkdir-test
  (is (= "mkdir -p dir"
         (stevedore/script (mkdir "dir" ~{:p true})))))

(deftest directory*-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (directory* {} "file1"))))

(deftest directory-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (first (build-actions [] (directory "file1")))))
  (testing "delete"
    (is (= (stevedore/checked-script "Delete directory file1" "rm -r -f file1")
           (first (build-actions
                   []
                   (directory "file1" :action :delete :recursive true)))))))

(deftest directories-test
  (is (= (str
          (stevedore/chain-commands
           (directory* {} "d1" :owner "o")
           (directory* {} "d2" :owner "o"))
          \newline)
         (first
          (build-actions
           []
           (directories ["d1" "d2"] :owner "o"))))))
