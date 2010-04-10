(ns pallet.resource.file-test
  (:use [pallet.resource.file] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(deftest rm-test
  (is (= "rm --force file1"
         (script (rm "file1" ~{:force true})))))

(deftest chown-test
  (is (= "chown  user1 file1"
         (script (chown "user1" "file1")))))

(deftest chgrp-test
  (is (= "chgrp  group1 file1"
         (script (chgrp "group1" "file1")))))

(deftest chmod-test
  (is (= "chmod  0666 file1"
         (script (chmod "0666" "file1")))))

(deftest tmpdir-test
  (is (= "${TMPDIR-/tmp}"
         (script (tmp-dir)))))

(deftest heredoc-script-test
  (is (= "cat > somepath <<EOF\nsomecontent\nEOF"
         (script (heredoc "somepath" "somecontent")))))

(deftest heredoc-test
  (is (= "cat > somepath <<EOF\nsomecontent\nEOF"
         (heredoc "somepath" "somecontent"))))

(deftest heredoc-test
  (is (= "cat > somepath <<'EOF'\nsomecontent\nEOF"
         (heredoc "somepath" "somecontent" :literal true))))

(deftest file-test
  (is (= "touch  file1\n"
         (build-resources [] (file "file1"))))
  (is (= "touch  file1\nchown  user1 file1\n"
         (build-resources [] (file "file1" :owner "user1"))))
  (is (= "touch  file1\nchown  user1 file1\n"
         (build-resources [] (file "file1" :owner "user1" :action :create))))
  (is (= "touch  file1\nchgrp  group1 file1\n"
         (build-resources [] (file "file1" :group "group1" :action :touch))))
  (is (= "rm --force file1\n"
         (build-resources [] (file "file1" :action :delete :force true)))))
