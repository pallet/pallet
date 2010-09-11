(ns pallet.resource.remote-file-test
  (:use pallet.resource.remote-file)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources phase]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.compute :as compute]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [clojure.contrib.io :as io]))

(use-fixtures :once with-ubuntu-script-template)

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(deftest remote-file*-test
  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path" "xxx"))
         (remote-file* {} "path" :content "xxx")))

  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path" "xxx")
          (stevedore/script (chown "o" "path"))
          (stevedore/script (chgrp "g" "path"))
          (stevedore/script (chmod "m" "path")))
         (remote-file*
          {} "path" :content "xxx" :owner "o" :group "g" :mode "m")))

  (testing "delete"
    (is (= (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (remote-file* {} "path" :action :delete :force true))))

  (with-temporary [tmp (tmpfile)]
    (is (= (str "remote-file " (.getPath tmp) "...\n" "...done\n")
           (bash-out (remote-file* {} (.getPath tmp) :content "xxx"))))
    (is (= "xxx\n" (slurp (.getPath tmp)))))

  (with-temporary [tmp (tmpfile)]
    (is (= (str "remote-file " (.getPath tmp) "...\n"
                "...done\n")
           (bash-out
            (remote-file* {} (.getPath tmp) :content "xxx" :chmod "0666"))))
    (is (= "xxx\n" (slurp (.getPath tmp)))))

  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path" "a 1\n"))
         (remote-file*
          {:node-type {:tag :n :image [:ubuntu]}}
          "path" :template "template/strint" :values {'a 1}))))

(deftest remote-file-test
  (is (= (stevedore/checked-commands
          "remote-file file1"
          (file/heredoc "file1" "somecontent"))
         (first (build-resources
                 [] (remote-file "file1" :content "somecontent")))))
  (is (= (stevedore/checked-commands
          "remote-file file1"
          (file/heredoc "file1" "somecontent" :literal true))
         (first
          (build-resources
           [] (remote-file "file1" :content "somecontent" :literal true)))))
  (is (= (stevedore/checked-script
          "remote-file file1"
          (var tmpfile @(mktemp prfXXXXX))
          (download-file "http://xx.com/abc" @tmpfile)
          (mv @tmpfile "file1")
          (echo "MD5 sum is" @(md5sum "file1")))
         (first (build-resources
                 [] (remote-file "file1" :url "http://xx.com/abc")))))
  (is (= (stevedore/checked-script
          "remote-file file1"
          (var tmpfile @(mktemp prfXXXXX))
          (download-file "http://xx.com/abc" @tmpfile)
          (mv @tmpfile "file1")
          (echo "MD5 sum is" @(md5sum "file1"))
          (chown "user1" "file1"))
         (first
          (build-resources
           [] (remote-file "file1" :url "http://xx.com/abc" :owner "user1")))))
  (is (= (stevedore/checked-script
          "remote-file file1"
          (if (|| (not (file-exists? "file1"))
                  (!= "abcd" @(md5sum "file1" "|" cut "-f1 -d' '")))
            ~(stevedore/chained-script
              (var tmpfile @(mktemp prfXXXXX))
              (download-file "http://xx.com/abc" @tmpfile)
              (mv @tmpfile "file1")))
          (echo "MD5 sum is" @(md5sum "file1"))
          (chown "user1" "file1"))
         (first
          (build-resources
           [:node-type {:image [:ubuntu]}]
           (remote-file
            "file1" :url "http://xx.com/abc" :md5 "abcd" :owner "user1")))))

  (is (= "echo \"remote-file file1...\"\n{ cp file2 file1 && chown  user1 file1; } || { echo remote-file file1 failed ; exit 1 ; } >&2 \necho \"...done\"\n"
         (first (build-resources
                 [] (remote-file
                     "file1" :remote-file "file2" :owner "user1")))))

  (is (thrown-with-msg? RuntimeException
        #".*/some/non-existing/file.*does not exist, is a directory, or is unreadable.*"
        (build-resources
         [] (remote-file
             "file1" :local-file "/some/non-existing/file" :owner "user1"))))

  (is (thrown-with-msg? RuntimeException
        #".*file1.*without content.*"
        (build-resources
         [] (remote-file "file1" :owner "user1"))))

  (with-temporary [tmp (tmpfile)]
    (is (re-find #"mv ~/pallet-transfer-[a-f0-9-]+ file1"
                 (first
                  (build-resources
                   [] (remote-file
                       "file1" :local-file (.getPath tmp)))))))

  (with-temporary [tmp (tmpfile)
                   target-tmp (tmpfile)]
    ;; this is convoluted to get around the "t" sticky bit on temp dirs
    (let [user (assoc utils/*admin-user*
                 :username (test-username) :no-sudo true)]
      (.delete target-tmp)
      (io/copy "text" tmp)
      (core/defnode tag [])
      (let [node (compute/make-unmanaged-node "tag" "localhost")
            request {:all-nodes [node]
                     :target-nodes [node]
                     :target-node node
                     :node-type tag
                     :user user}]
        (core/lift*
         nil "" {tag node} nil
         [(phase
           (remote-file
            (.getPath target-tmp)
            :local-file (.getPath tmp) :mode "0666"))]
         request core/*middleware*)
        (is (.canRead target-tmp))
        (is (= "text" (slurp (.getPath target-tmp))))
        (core/lift*
         nil "" {tag node} nil
         [(phase
           (exec-script/exec-script
            (rm ~(.getPath target-tmp))))]
         request core/*middleware*))
      (is (not (.exists target-tmp))))))
