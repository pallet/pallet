(ns pallet.actions.direct.directory-test
  (:use pallet.actions.direct.directory)
  (:require
   [pallet.stevedore :as stevedore])
  (:use
   clojure.test
   [pallet.action :only [action-fn]]
   [pallet.actions :only [directories directory]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.test-utils
    :only [with-ubuntu-script-template with-bash-script-language]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 (logging-threshold-fixture))

(def directory* (action-fn directory :direct))

(deftest directory*-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (binding [pallet.action-plan/*defining-context* nil]
           (-> (directory* {} "file1") first second)))))

(deftest directory-test
  (is (= (stevedore/checked-commands "Directory file1" "mkdir -p file1")
         (first (build-actions {} (directory "file1")))))
  (is (= (stevedore/checked-commands
          "Directory file1"
          "mkdir -m \"0755\" -p file1"
          "chown --recursive u file1"
          "chgrp --recursive g file1"
          "chmod 0755 file1")
         (first
          (build-actions {}
            (directory "file1" :owner "u" :group "g" :mode "0755")))))
  (testing "non-recursive"
    (is (= (stevedore/checked-commands
            "Directory file1"
            "mkdir -m \"0755\" -p file1"
            "chown u file1"
            "chgrp g file1"
            "chmod 0755 file1")
           (first
            (build-actions {}
              (directory
               "file1" :owner "u" :group "g" :mode "0755" :recursive false))))))
  (testing "delete"
    (is (= (stevedore/checked-script
            "Delete directory file1"
            "rm --recursive --force file1")
           (first
            (build-actions {}
              (directory "file1" :action :delete :recursive true)))))))

(deftest directories-test
  (is (= (str
          (binding [pallet.action-plan/*defining-context* nil]
            (stevedore/chain-commands
             (-> (directory* {} "d1" :owner "o") first second)
             (-> (directory* {} "d2" :owner "o") first second)))
          \newline)
         (first
          (build-actions {}
            (directories ["d1" "d2"] :owner "o"))))))
