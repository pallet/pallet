(ns pallet.resource.remote-file-test
  (:use [pallet.resource.remote-file] :reload-all)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources phase]]
        clojure.test
        pallet.test-utils)
  (:require
    pallet.compat
   [pallet.core :as core]
   [pallet.compute :as compute]
   [pallet.utils :as utils]))

(pallet.compat/require-contrib)

(deftest remote-file-test
  (is (= "cat > file1 <<EOF\nsomecontent\nEOF\n"
         (build-resources
          [] (remote-file "file1" :content "somecontent"))))
  (is (= "cat > file1 <<'EOF'\nsomecontent\nEOF\n"
         (build-resources
          [] (remote-file "file1" :content "somecontent" :literal true))))
  (is (= "wget -O file1 http://xx.com/abc\necho MD5 sum is $(md5sum file1)\n"
         (build-resources
          [] (remote-file "file1" :url "http://xx.com/abc"))))
  (is (= "wget -O file1 http://xx.com/abc\necho MD5 sum is $(md5sum file1)\nchown  user1 file1\n"
         (build-resources
          [] (remote-file "file1" :url "http://xx.com/abc" :owner "user1"))))
  (is (= "if [ ! -e file1 ]; then\nif [ ! \\( \"abcd\" == \"$(md5sum file1 | cut -f1 -d' ')\" \\) ]; then wget -O file1 http://xx.com/abc;fi\nfi\necho MD5 sum is $(md5sum file1)\nchown  user1 file1\n"
         (build-resources
          [] (remote-file
              "file1" :url "http://xx.com/abc" :md5 "abcd" :owner "user1"))))

  (is (= "cp file2 file1\nchown  user1 file1\n"
         (build-resources
          [] (remote-file
              "file1" :remote-file "file2" :owner "user1"))))

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
    (is (re-find #"mv pallet-transfer-[a-f0-9-]+ file1"
                 (build-resources
                  [] (remote-file
                      "file1" :local-file (.getPath tmp))))))

  (with-temporary [tmp (tmpfile)
                   target-tmp (tmpfile)]
    (.delete target-tmp)
    (io/copy "text" tmp)
    (core/defnode tag [])
    (core/apply-phases-to-node
     nil (compute/make-unmanaged-node "tag" "localhost")
     [(phase
       (remote-file (.getPath target-tmp) :local-file (.getPath tmp)))]
     (assoc utils/*admin-user* :no-sudo true))
    (is (.canRead target-tmp))
    (is (= "text" (slurp (.getPath target-tmp))))))
