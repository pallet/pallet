(ns pallet.resource.remote-file-test
  (:use pallet.resource.remote-file)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources phase]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.compute :as compute]
   [pallet.execute :as execute]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [clojure.contrib.io :as io]))

(use-fixtures :once with-ubuntu-script-template)

(deftest remote-file*-test
  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path.new" "xxx")
          (stevedore/chained-script
           (if (file-exists? "path.new")
             (mv -f " path.new" "path"))
           ((md5sum "path") > "path.md5")
           (echo "MD5 sum is" @(cat "path.md5"))))
         (remote-file* {} "path" :content "xxx" :no-versioning true)))

  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path.new" "xxx")
          (stevedore/chained-script
           (if (file-exists? "path.new")
             (mv -f " path.new" "path"))
           (chown "o" "path")
           (chgrp "g" "path")
           (chmod "m" "path")
           ((md5sum "path") > "path.md5")
           (echo "MD5 sum is" @(cat "path.md5"))))
         (remote-file*
          {} "path" :content "xxx" :owner "o" :group "g" :mode "m"
          :no-versioning true)))

  (testing "delete"
    (is (= (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (remote-file* {} "path" :action :delete :force true))))

  (with-temporary [tmp (tmpfile)]
    (.delete tmp)
    (is (= (str "remote-file " (.getPath tmp) "...\n"
                "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                (.getPath tmp) "\n"
                "...done\n")
           (->
            (pallet.core/lift
             {{:tag :tag} (pallet.compute/make-localhost-node)}
             :phase #(remote-file % (.getPath tmp) :content "xxx")
             :compute nil
             :middleware pallet.core/execute-with-local-sh)
            :results :localhost first second first :out)))
    (is (= "xxx\n" (slurp (.getPath tmp)))))

  (testing "overwrite on existing content and no md5"
    (with-temporary [tmp (tmpfile)]
      (is  (re-matches
            (java.util.regex.Pattern/compile
             "remote-file .*....*MD5 sum is 6de9439834c9147569741d3c9c9fc010 .*...done."
             (bit-or java.util.regex.Pattern/MULTILINE
                     java.util.regex.Pattern/DOTALL))
            (->
             (pallet.core/lift
              {{:tag :tag} (pallet.compute/make-localhost-node)}
              :phase #(remote-file
                       % (.getPath tmp) :content "xxx")
              :compute nil
              :middleware pallet.core/execute-with-local-sh)
             :results :localhost first second first :out)))
      (is (= "xxx\n" (slurp (.getPath tmp))))))

  (binding [install-new-files nil]
    (is (= (stevedore/checked-commands
            "remote-file path"
            (file/heredoc "path.new" "a 1\n"))
           (remote-file*
            {:node-type {:tag :n :image {:os-family :ubuntu}}}
            "path" :template "template/strint" :values {'a 1}
            :no-versioning true)))))

(deftest remote-file-test
  (core/with-admin-user (assoc utils/*admin-user* :username (test-username))
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
      (is (re-find #"mv -f ~/pallet-transfer-[a-f0-9-]+ file1"
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
        (core/defnode tag {:tag "localhost"})
        (let [node (compute/make-localhost-node)]
          (testing "local-file"
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp) :local-file (.getPath tmp)
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "content"
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp) :content "$(hostname)"
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= (:out (execute/sh-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content"
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp) :content "$text123" :literal true
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "$text123\n" (slurp (.getPath target-tmp)))))
          (testing "remote-file"
            (io/copy "text" tmp)
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp) :remote-file (.getPath tmp)
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "url"
            (io/copy "urltext" tmp)
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp)
                      :url (str "file://" (.getPath tmp))
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urltext" (slurp (.getPath target-tmp)))))
          (testing "url with md5"
            (io/copy "urlmd5text" tmp)
            (core/lift
             {tag node}
             :phase #(remote-file
                      % (.getPath target-tmp)
                      :url (str "file://" (.getPath tmp))
                      :md5 (stevedore/script @(md5sum ~(.getPath tmp)))
                      :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urlmd5text" (slurp (.getPath target-tmp)))))
          (testing "url with md5 urls"
            (.delete target-tmp)
            (io/copy "urlmd5urltext" tmp)
            (let [md5path (str (.getPath tmp) ".md5")]
              (core/lift
               {tag node}
               :phase (resource/phase
                       (exec-script/exec-script
                        ((md5sum ~(.getPath tmp)) > ~md5path))
                       (remote-file
                        (.getPath target-tmp)
                        :url (str "file://" (.getPath tmp))
                        :md5-url (str "file://" md5path)
                        :mode "0666"))
               :user user))
            (is (.canRead target-tmp))
            (is (= "urlmd5urltext" (slurp (.getPath target-tmp)))))
          (testing "delete action"
            (core/lift
             {tag node}
             :phase #(remote-file % (.getPath target-tmp) :action :delete)
             :user user)
            (is (not (.exists target-tmp)))))))))
