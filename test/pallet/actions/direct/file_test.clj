(ns pallet.actions.direct.file-test
  (:use
   [pallet.actions :only [file fifo symbolic-link sed]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.monad :only [phase-pipeline]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils
    :only [with-ubuntu-script-template with-bash-script-language]]
   clojure.test)
  (:require
   pallet.actions.direct.file
   [pallet.stevedore :as stevedore]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 (logging-threshold-fixture))

(deftest file-test
  (is (= (stevedore/checked-script "file file1" (touch file1))
         (first (build-actions {} (file "file1")))))
  (is (= (stevedore/checked-script "ctx\nfile file1" (touch file1))
         (first
          (build-actions {:phase-context "ctx"}
            (file "file1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-actions {}
                  (file "file1" :owner "user1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-actions {}
                  (file "file1" :owner "user1" :action :create)))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chgrp group1 file1))
         (first (build-actions {}
                  (file "file1" :group "group1" :action :touch)))))
  (is (= (stevedore/checked-script
          "delete file file1"
          ("rm" "--force" file1))
         (first (build-actions {}
                  (file "file1" :action :delete :force true))))))

(deftest sed-test
  (is (= (stevedore/checked-commands
          "sed file path"
          "sed -i -e \"s|a|b|\" path"
          (str "(cp=$(readlink -f path) && cd $(dirname ${cp}) && "
               "md5sum $(basename ${cp}) > path.md5\n)"))
         (first
          (build-actions {}
            (sed "path" {"a" "b"} :seperator "|")))))
  (is
   (build-actions {}
     (sed "path" {"a" "b"} :seperator "|"))))
