(ns pallet.actions.direct.file-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions.direct.file
    :refer [fifo* file* sed* symbolic-link* write-md5-for-file]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore :refer [script]]
   [pallet.test-utils
    :refer [with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 with-no-source-line-comments)

(deftest file-test
  (is (=
       (stevedore/checked-script "file file1" ("touch" file1))
       (file* nil "file1" {})))
  (is (=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (file* nil "file1" {:owner "user1"})))
  (is (=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (file* nil "file1" {:owner "user1" :action :create})))
  (is (=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chgrp" group1 file1))
       (file* nil "file1" {:group "group1" :action :touch})))
  (is (=
       (stevedore/checked-script
        "delete file file1"
        ("rm" "--force" file1))
       (file* nil "file1" {:action :delete :force true}))))

(deftest sed-test
  (is (=
       (stevedore/checked-commands
        "sed file path"
        (script (~lib/sed-file "path" {"a" "b"} {:seperator "|"}))
        (write-md5-for-file "path" "path.md5"))
       (sed* {} "path" {"a" "b"} {:seperator "|"}))))

(deftest fifo-test
  (is (=
       (stevedore/checked-commands
        "fifo path"
        (script (if-not (file-exists? path)
                ("mkfifo" path))))
       (fifo* {} "path" {})))
  (is (=
       (stevedore/checked-commands
        "fifo path"
        (script ("rm" "--force" "path")))
       (fifo* {} "path" {:action :delete :force true}))))

(deftest symbolic-link-test
  (is (=
       (stevedore/checked-commands
        "Link path as path2"
        (script (~lib/ln path path2 :force true :symbolic true)))
       (symbolic-link* {} "path" "path2"))))
