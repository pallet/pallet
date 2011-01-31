(ns pallet.resource.file-test
  (:use pallet.resource.file)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once with-ubuntu-script-template)

(deftest rm-test
  (is (= "rm --force file1"
         (script (rm "file1" :force true)))))

(deftest mv-test
  (is (= "mv --backup=numbered file1 file2"
         (script (mv "file1" "file2" :backup :numbered)))))

(deftest ln-test
  (is (= "ln -s file1 file2"
         (script (ln "file1" "file2" :symbolic true)))))

(deftest chown-test
  (is (= "chown user1 file1"
         (script (chown "user1" "file1")))))

(deftest chgrp-test
  (is (= "chgrp group1 file1"
         (script (chgrp "group1" "file1")))))

(deftest chmod-test
  (is (= "chmod 0666 file1"
         (script (chmod "0666" "file1")))))

(deftest tmpdir-test
  (is (= "${TMPDIR-/tmp}"
         (script (tmp-dir)))))

(deftest heredoc-test
  (is (= "{ cat > somepath <<EOFpallet\nsomecontent\nEOFpallet\n }"
         (script (heredoc "somepath" "somecontent" {})))))

(deftest heredoc-literal-test
  (is (= "{ cat > somepath <<'EOFpallet'\nsomecontent\nEOFpallet\n }"
         (script (heredoc "somepath" "somecontent" {:literal true})))))

(deftest file-test
  (is (= (stevedore/checked-script "file file1" (touch file1))
         (first (build-resources [] (file "file1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-resources [] (file "file1" :owner "user1")))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chown user1 file1))
         (first (build-resources
                 [] (file "file1" :owner "user1" :action :create)))))
  (is (= (stevedore/checked-script
          "file file1"
          (touch file1)
          (chgrp group1 file1))
         (first (build-resources
                 [] (file "file1" :group "group1" :action :touch)))))
  (is (= (stevedore/checked-script
          "delete file file1"
          ("rm" "--force" file1))
         (first (build-resources
                 [] (file "file1" :action :delete :force true))))))

(deftest sed-file-test
  (testing "explicit separator"
    (is (= "sed -i -e \"s|a|b|\" path"
           (stevedore/script (sed-file "path" {"a" "b"} {:seperator "|"})))))
  (testing "computed separator"
    (is (= "sed -i -e \"s/a/b/\" path"
           (stevedore/script (sed-file "path" {"a" "b"} {}))))
    (is (= "sed -i -e \"s_a/_b_\" path"
           (stevedore/script (sed-file "path" {"a/" "b"} {}))))
    (is (= "sed -i -e \"s_a_b/_\" path"
           (stevedore/script (sed-file "path" {"a" "b/"} {}))))
    (is (= "sed -i -e \"s*/_|:%!@*b*\" path"
           (stevedore/script (sed-file "path" {"/_|:%!@" "b"} {})))))
  (testing "restrictions"
    (is (= "sed -i -e \"1 s/a/b/\" path"
           (stevedore/script (sed-file "path" {"a" "b"} {:restriction "1"}))))
    (is (= "sed -i -e \"/a/ s/a/b/\" path"
           (stevedore/script
            (sed-file "path" {"a" "b"} {:restriction "/a/"})))))
  (testing "other commands"
    (is (= "sed -i -e \"1 a\" path"
           (stevedore/script (sed-file "path" "a" {:restriction "1"}))))))

(deftest sed-test
  (is (= (stevedore/checked-commands
          "sed file path"
          "sed -i -e \"s|a|b|\" path"
          "md5sum path > path.md5")
         (sed* {} "path" {"a" "b"} :seperator "|")))
  (is
   (build-resources
    []
    (sed "path" {"a" "b"} :seperator "|"))))

(deftest make-temp-file-test
  (is (= "$(mktemp \"prefixXXXXX\")"
         (stevedore/script (make-temp-file "prefix")))))

(deftest download-file-test
  (is
   (stevedore/script
    (download-file
     "http://server.com/" "/path")))
  (is (= "if test $(which curl); then curl -o \"/path\" --retry 5 --silent --show-error --fail --location --proxy localhost:3812 \"http://server.com/\";else\nif test $(which wget); then wget -O \"/path\" --tries 5 --no-verbose -e \"http_proxy = http://localhost:3812\" -e \"ftp_proxy = http://localhost:3812\" \"http://server.com/\";else\necho No download utility available\nexit 1\nfi\nfi"
         (stevedore/script
          (download-file
           "http://server.com/" "/path" :proxy "http://localhost:3812")))))
