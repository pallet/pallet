(ns pallet.resource.remote-file-test
  (:use pallet.resource.remote-file)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources phase]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.resource.lib :as lib]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.compute :as compute]
   [pallet.compute.jclouds :as jclouds]
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
             (do
               (mv -f " path.new" "path")))
           ((md5sum "path") > "path.md5")
           (echo "MD5 sum is" @(cat "path.md5"))))
         (remote-file* {} "path" :content "xxx" :no-versioning true)))

  (is (= (stevedore/checked-commands
          "remote-file path"
          (file/heredoc "path.new" "xxx")
          (stevedore/chained-script
           (if (file-exists? "path.new")
             (do
               (mv -f " path.new" "path")))
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

  (utils/with-temporary [tmp (utils/tmpfile)]
    (.delete tmp)
    (is (= (str "remote-file " (.getPath tmp) "...\n"
                "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                (.getPath tmp) "\n"
                "...done\n")
           (->
            (pallet.core/lift
             {{:tag :tag} (jclouds/make-localhost-node)}
             :phase #(remote-file % (.getPath tmp) :content "xxx")
             :compute nil
             :middleware pallet.core/execute-with-local-sh)
            :results :localhost second second first :out)))
    (is (= "xxx\n" (slurp (.getPath tmp)))))

  (testing "overwrite on existing content and no md5"
    (utils/with-temporary [tmp (utils/tmpfile)]
      (is  (re-matches
            (java.util.regex.Pattern/compile
             (str "remote-file .*....*MD5 sum is "
                  "6de9439834c9147569741d3c9c9fc010 .*...done.")
             (bit-or java.util.regex.Pattern/MULTILINE
                     java.util.regex.Pattern/DOTALL))
            (->
             (pallet.core/lift
              {{:tag :tag} (jclouds/make-localhost-node)}
              :phase #(remote-file
                       % (.getPath tmp) :content "xxx")
              :compute nil
              :middleware pallet.core/execute-with-local-sh)
             :results :localhost second second first :out)))
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

    (utils/with-temporary [tmp (utils/tmpfile)]
      (is (re-find #"mv -f --backup=numbered file1.new file1"
                   (first
                    (build-resources
                     [] (remote-file
                         "file1" :local-file (.getPath tmp)))))))

    (utils/with-temporary [tmp (utils/tmpfile)
                           target-tmp (utils/tmpfile)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (assoc utils/*admin-user*
                   :username (test-username) :no-sudo true)]
        (.delete target-tmp)
        (io/copy "text" tmp)
        (core/defnode tag {:tag "localhost"})
        (let [node (jclouds/make-localhost-node)]
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
                      :mode "0666" :flag-on-changed :changed)
             :user user)
            (is (.canRead target-tmp))
            (is (= (:out (execute/sh-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content unchanged"
            (is
             (re-find
              #"correctly unchanged"
              (->
               (core/lift
                {tag node}
                :phase (resource/phase
                        (remote-file
                         (.getPath target-tmp) :content "$(hostname)"
                         :mode "0666" :flag-on-changed :changed)
                        (exec-script/exec-script
                         (if (== (flag? :changed) "1")
                           (echo "incorrect!" (flag? :changed) "!")
                           (echo "correctly unchanged"))))
                :user user)
               :results :localhost second second first :out)))
            (is (.canRead target-tmp))
            (is (= (:out (execute/sh-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content changed"
            (is
             (re-find
              #"correctly changed"
              (->
               (core/lift
                {tag node}
                :phase (resource/phase
                        (remote-file
                         (.getPath target-tmp) :content "abc"
                         :mode "0666" :flag-on-changed :changed)
                        (exec-script/exec-script
                         (if (== (flag? :changed) "1")
                           (echo "correctly changed")
                           (echo "incorrect!" (flag? :changed) "!"))))
                :user user)
               :results :localhost second second first :out)))
            (is (.canRead target-tmp))
            (is (= "abc\n"
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

(resource/deflocal check-content
  (*check-content
   [request path content path-atom]
   (is (= content (slurp path)))
   (reset! path-atom path)))

(deftest with-remote-file-test
  (core/with-admin-user (assoc utils/*admin-user* :username (test-username))
    (utils/with-temporary [remote-file (utils/tmpfile)]
      (let [user (assoc utils/*admin-user*
                   :username (test-username) :no-sudo true)]
        (io/copy "text" remote-file)
        (core/defnode tag {:tag "localhost"})
        (testing "with local ssh"
          (let [node (jclouds/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {tag node}
               :phase #(with-remote-file
                         % check-content (.getPath remote-file)
                         "text" path-atom)
               :user user)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))
        (testing "with local shell"
          (let [node (jclouds/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {tag node}
               :phase #(with-remote-file
                         % check-content (.getPath remote-file)
                         "text" path-atom)
               :user user
               :middleware pallet.core/execute-with-local-sh)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))
