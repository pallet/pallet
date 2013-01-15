(ns pallet.action.file-test
  (:use pallet.action.file)
  (:use [pallet.stevedore :only [script]]
        clojure.test)
  (:require
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language)

(def ^{:private true} sed* (action/action-fn file/sed))

(deftest file-test
  (is (= (stevedore/checked-script "file file1" (touch file1))
         (first (build-actions/build-actions {} (file "file1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-actions/build-actions
                 {} (file "file1" :owner "user1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-actions/build-actions
                 {} (file "file1" :owner "user1" :action :create)))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chgrp group1 file1))
         (first (build-actions/build-actions
                 {} (file "file1" :group "group1" :action :touch)))))
  (is (= (stevedore/checked-script
          "delete file file1"
          ("rm" "--force" file1))
         (first (build-actions/build-actions
                 {} (file "file1" :action :delete :force true))))))

(deftest sed-test
  (is (= (stevedore/checked-commands
          "sed file path"
          "sed -i -e \"s|a|b|\" path"
          (str "(cp=$(readlink -f path) && cd $(dirname ${cp}) && "
               "md5sum $(basename ${cp}) > path.md5\n)"))
         (sed* {} "path" {"a" "b"} :seperator "|")))
  (is
   (build-actions/build-actions
    {}
    (sed "path" {"a" "b"} :seperator "|"))))
