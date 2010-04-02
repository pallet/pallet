(ns pallet.resource.remote-file-test
  (:use [pallet.resource.remote-file] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(deftest remote-file-test
  (is (= "cat > file1 <<EOF\nsomecontent\nEOF\n"
         (build-resources (remote-file "file1" :content "somecontent"))))
  (is (= "wget -O file1 http://xx.com/abc\n"
         (build-resources (remote-file "file1" :source "http://xx.com/abc"))))
  (is (= "wget -O file1 http://xx.com/abc\nchown  user1 file1\n"
         (build-resources
          (remote-file "file1" :source "http://xx.com/abc" :owner "user1"))))
  (is (= "if [ ! \\( -e file1 -a \\( \"abcd\" == \"$(md5sum file1)\" \\) \\) ]; then wget -O file1 http://xx.com/abc;fi\nchown  user1 file1\n"
         (build-resources
          (remote-file
           "file1" :source "http://xx.com/abc" :md5 "abcd" :owner "user1")))))
