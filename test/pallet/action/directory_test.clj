(ns pallet.action.directory-test
  (:use pallet.action.directory)
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils])
  (:use
   clojure.test
   pallet.build-actions))

(use-fixtures :once test-utils/with-ubuntu-script-template)

(def directory* (action/action-fn directory))

(deftest directory*-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (directory* {} "file1"))))

(deftest directory-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (first (build-actions {} (directory "file1")))))
  (is (= (stevedore/checked-commands
          "Directory file1"
          "mkdir -m \"0755\" -p file1"
          "chown --recursive u file1"
          "chgrp --recursive g file1"
          "chmod 0755 file1")
         (first (build-actions
                 {}
                 (directory "file1" :owner "u" :group "g" :mode "0755")))))
  (testing "non-recursive"
    (is (= (stevedore/checked-commands
            "Directory file1"
            "mkdir -m \"0755\" -p file1"
            "chown u file1"
            "chgrp g file1"
            "chmod 0755 file1")
           (first
            (build-actions
             {}
             (directory
              "file1" :owner "u" :group "g" :mode "0755" :recursive false))))))
  (testing "delete"
    (is (= (stevedore/checked-script
            "Delete directory file1"
            "rm --recursive --force file1")
           (first (build-actions
                   {}
                   (directory "file1" :action :delete :recursive true)))))))

(deftest directories-test
  (is (= (str
          (stevedore/chain-commands
           (directory* {} "d1" :owner "o")
           (directory* {} "d2" :owner "o"))
          \newline)
         (first
          (build-actions
           {}
           (directories ["d1" "d2"] :owner "o"))))))
