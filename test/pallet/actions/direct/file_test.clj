(ns pallet.actions.direct.file-test
  (:use
   [pallet.actions :only [file fifo symbolic-link sed]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils
    :only [with-ubuntu-script-template with-bash-script-language]]
   clojure.test)
  (:require
   [pallet.actions.direct.file :refer [write-md5-for-file]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 (logging-threshold-fixture))

(deftest file-test
  (is (script-no-comment=
       (stevedore/checked-script "file file1" ("touch" file1))
       (first (build-actions {} (file "file1")))))
  (testing "context label"
    (is (script-no-comment=
         (stevedore/checked-script "ctx: file file1" ("touch" file1))
         (first
          (build-actions {:phase-context "ctx"}
            (file "file1"))))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (first (build-actions {}
                (file "file1" :owner "user1")))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (first (build-actions {}
                (file "file1" :owner "user1" :action :create)))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chgrp" group1 file1))
       (first (build-actions {}
                (file "file1" :group "group1" :action :touch)))))
  (is (script-no-comment=
       (stevedore/checked-script
        "delete file file1"
        ("rm" "--force" file1))
       (first (build-actions {}
                (file "file1" :action :delete :force true))))))

(deftest sed-test
  (is (script-no-comment=
       (stevedore/checked-commands
        "sed file path"
        (script (~lib/sed-file "path" {"a" "b"} {:seperator "|"}))
        (write-md5-for-file "path" "path.md5"))
       (first
        (build-actions {}
          (sed "path" {"a" "b"} :seperator "|")))))
  (is
   (build-actions {}
     (sed "path" {"a" "b"} :seperator "|"))))
