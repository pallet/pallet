(ns pallet.action.directory-test
  (:use pallet.action.directory)
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils])
  (:use
   clojure.test
   pallet.build-actions
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 (logging-threshold-fixture))

(def directory* (action/action-fn directory))

(deftest directory*-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (binding [pallet.action-plan/*defining-context* nil]
           (directory* {} "file1")))))

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
          (binding [pallet.action-plan/*defining-context* nil]
            (stevedore/chain-commands
             (directory* {} "d1" :owner "o")
             (directory* {} "d2" :owner "o"))))
         (first
          (build-actions
           {}
           (directories ["d1" "d2"] :owner "o"))))))
