(ns pallet.actions.direct.remote-file-test
  (:use
   [pallet.actions
    :only [exec-script transfer-file transfer-file-to-local remote-file
           with-remote-file]]
   [pallet.actions-impl
    :only [remote-file-action *install-new-files* *force-overwrite*]]
   [pallet.node-value :only [node-value]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils :only [clj-action verify-flag-not-set verify-flag-set]]
   clojure.test)
  (:require
   pallet.actions.direct.remote-file
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.test-executors :as test-executors]
   [pallet.utils :as utils]
   [clojure.java.io :as io]
   [clojure.tools.logging :as logging]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 (logutils/logging-threshold-fixture))

(def remote-file* (action/action-fn remote-file-action :direct))

(defn- local-test-user
  []
  (assoc utils/*admin-user* :username (test-utils/test-username) :no-sudo true))

(deftest remote-file*-test
  (is remote-file*)
  (testing "url"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             (~lib/download-file "http://a.com/b" "path.new")
             (if (file-exists? "path.new")
               (do
                 (mv -f "path.new" path)))))
           (binding [pallet.action-plan/*defining-context* nil]
             (first
              (remote-file*
               {} "path"
               {:url "http://a.com/b" :no-versioning true
                :install-new-files true}))))))
  (testing "url with proxy"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             (~lib/download-file
              "http://a.com/b" "path.new" :proxy "http://proxy/")
             (if (file-exists? "path.new")
               (do
                 (mv -f "path.new" path)))))
           (binding [pallet.action-plan/*defining-context* nil]
             (first
              (remote-file*
               {:environment {:proxy "http://proxy/"}}
               "path" {:url "http://a.com/b" :no-versioning true
                       :install-new-files true}))))))

  (testing "no-versioning"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/script (~lib/heredoc "path.new" "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? "path.new")
               (do
                 (~lib/mv "path.new" path :force true)))))
           (binding [pallet.action-plan/*defining-context* nil]
             (first
              (remote-file* {} "path" {:content "xxx" :no-versioning true
                                       :install-new-files true}))))))

  (testing "no-versioning with owner, group and mode"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/script (~lib/heredoc "path.new" "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? "path.new")
               (do
                 (~lib/mv "path.new" "path" :force true)))
             (~lib/chown "o" "path")
             (~lib/chgrp "g" "path")
             (~lib/chmod "m" "path")))
           (binding [pallet.action-plan/*defining-context* nil]
             (first
              (remote-file*
               {} "path" {:content "xxx" :owner "o" :group "g" :mode "m"
                          :no-versioning true :install-new-files true}))))))

  (testing "delete"
    (is (= (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (binding [pallet.action-plan/*defining-context* nil]
             (first
              (remote-file* {} "path" {:action :delete :force true}))))))

  (testing "content"
    (utils/with-temporary [tmp (utils/tmpfile)]
      (.delete tmp)
      (is (= (str "remote-file " (.getPath tmp) "...\n"
                  "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                  (.getPath tmp) "\n"
                  "...done\n")
             (let [session (pallet.core/lift
                            {(core/group-spec "local")
                             (test-utils/make-localhost-node
                              :group-name "local")}
                            :phase (remote-file (.getPath tmp) :content "xxx")
                            :compute nil
                            :user (local-test-user))]
               (logging/infof "r-f-t content: session %s" session)
               (-> session :results :localhost second second first :out))))
      (is (= "xxx\n" (slurp (.getPath tmp))))))

  (testing "overwrite on existing content and no md5"
    ;; note that the lift has to run with the same user as the java
    ;; process, otherwise there will be permission errors
    (utils/with-temporary [tmp (utils/tmpfile)]
      (let [session (pallet.core/lift
                     {(core/group-spec "local")
                      (test-utils/make-localhost-node
                       :group-name "local")}
                     :phase (remote-file (.getPath tmp) :content "xxx")
                     :compute nil
                     :user (local-test-user)
                     :executor test-executors/test-executor)]
        (logging/infof
         "r-f-t overwrite on existing content and no md5: session %s"
         session)
        (is (re-matches
             (java.util.regex.Pattern/compile
              (str "remote-file .*...done.")
              (bit-or java.util.regex.Pattern/MULTILINE
                      java.util.regex.Pattern/DOTALL))
             (-> session :results :localhost second second first :out))
            (is (= "xxx\n"
                   (slurp (.getPath tmp)))
                (-> session :results :localhost))))))
  (script/with-script-context [:ubuntu]
    (is (=
         (stevedore/checked-script
          "remote-file path"
          (~lib/heredoc "path.new" "a 1\n" {}))
         (binding [pallet.action-plan/*defining-context* nil]
           (first
            (remote-file*
             {:server {:group-name :n :image {:os-family :ubuntu}}}
             "path"
             {:template "template/strint" :values {'a 1}
              :no-versioning true :install-new-files nil})))))))

(deftest remote-file-test
  (core/with-admin-user
    (local-test-user)
    (is (thrown-with-msg? RuntimeException
          #".*/some/non-existing/file.*does not exist, is a directory, or is unreadable.*"
          (build-actions/build-actions
           {} (remote-file
               "file1" :local-file "/some/non-existing/file" :owner "user1"))))
    (is (=
         (str
          "{:error {:type :pallet/action-execution-error, "
          ":context nil, "
          ":message \"Unexpected exception: "
          "remote-file file1 specified without content.\", :cause "
          "#<IllegalArgumentException java.lang.IllegalArgumentException: "
          "remote-file file1 specified without content.>}}")
         (first (build-actions/build-actions
                 {} (remote-file "file1" :owner "user1")))))

    (utils/with-temporary [tmp (utils/tmpfile)]
      (is (re-find #"mv -f --backup=\"numbered\" file1.new file1"
                   (first
                    (build-actions/build-actions
                     {} (remote-file
                         "file1" :local-file (.getPath tmp)))))))

    (utils/with-temporary [tmp (utils/tmpfile)
                           target-tmp (utils/tmpfile)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (local-test-user)
            log-action (clj-action [session]
                         (logging/info "local-file test")
                         [nil session])]
        (.delete target-tmp)
        (io/copy "text" tmp)
        (let [local (core/group-spec "local")
              node (test-utils/make-localhost-node :group-name "local")]
          (testing "local-file"
            (logging/debugf "local-file is %s" (.getPath tmp))
            (let [result (core/lift
                          {local node}
                          :phase (phase/phase-fn
                                  (log-action)
                                  (remote-file
                                   (.getPath target-tmp)
                                   :local-file (.getPath tmp)
                                   :mode "0666"))
                          :user user)]
              (is (some #(= node %) (:all-nodes result))))
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp))))
            (is (slurp (str (.getPath target-tmp) ".md5")))
            (testing "with md5 guard"
              (logging/info "remote-file test: local-file with md5 guard")
              (let [result (core/lift
                            {local node}
                            :phase [(log-action)
                                    (remote-file
                                     (.getPath target-tmp)
                                     :local-file (.getPath tmp)
                                     :mode "0666")]
                            :user user)]
                (is (some #(= node %) (:all-nodes result))))))
          (testing "content"
            (core/lift
             {local node}
             :phase (remote-file (.getPath target-tmp) :content "$(hostname)"
                                 :mode "0666" :flag-on-changed :changed)
             :user user)
            (is (.canRead target-tmp))
            (is (= (:out (local/local-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content unchanged"
            (let [a (atom nil)]
              (core/lift
               {local node}
               :phase (phase/phase-fn
                       [nv (remote-file
                            (.getPath target-tmp) :content "$(hostname)"
                            :mode "0666" :flag-on-changed :changed)]
                       (verify-flag-not-set :changed)
                       ((clj-action
                            [session nv]
                          (reset! a true)
                          (is (nil? (seq (:flags nv))))
                          [nil session])
                        nv))
               :user user)
              (is @a)
              (is (.canRead target-tmp))
              (is (= (:out (local/local-script "hostname"))
                     (slurp (.getPath target-tmp))))))
          (testing "content changed"
            (let [a (atom nil)]
              (core/lift
               {local node}
               :phase (phase/phase-fn
                       [nv (remote-file
                            (.getPath target-tmp) :content "abc"
                            :mode "0666" :flag-on-changed :changed)]
                       (verify-flag-set :changed)
                       ((clj-action
                            [session nv]
                          (reset! a true)
                          (is (:flags nv))
                          (is ((:flags nv) :changed))
                          [nil session])
                        nv))
               :user user)
              (is @a))
            (is (.canRead target-tmp))
            (is (= "abc\n"
                   (slurp (.getPath target-tmp)))))
          (testing "content"
            (core/lift
             {local node}
             :phase (remote-file
                     (.getPath target-tmp) :content "$text123" :literal true
                     :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "$text123\n" (slurp (.getPath target-tmp)))))
          (testing "remote-file"
            (io/copy "text" tmp)
            (core/lift
             {local node}
             :phase (remote-file
                     (.getPath target-tmp) :remote-file (.getPath tmp)
                     :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "url"
            (io/copy "urltext" tmp)
            (core/lift
             {local node}
             :phase (remote-file
                     (.getPath target-tmp)
                     :url (str "file://" (.getPath tmp))
                     :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urltext" (slurp (.getPath target-tmp)))))
          (testing "url with md5"
            (io/copy "urlmd5text" tmp)
            (core/lift
             {local node}
             :phase (remote-file
                     (.getPath target-tmp)
                     :url (str "file://" (.getPath tmp))
                     :md5 (stevedore/script @(~lib/md5sum ~(.getPath tmp)))
                     :mode "0666")
             :user user)
            (is (.canRead target-tmp))
            (is (= "urlmd5text" (slurp (.getPath target-tmp)))))
          (testing "url with md5 urls"
            (.delete target-tmp)
            (io/copy "urlmd5urltext" tmp)
            (let [md5path (str (.getPath tmp) ".md5")]
              (core/lift
               {local node}
               :phase (phase/phase-fn
                       (exec-script
                        ((~lib/md5sum ~(.getPath tmp)) > ~md5path))
                       (remote-file
                        (.getPath target-tmp)
                        :url (str "file://" (.getPath tmp))
                        :md5-url (str "file://" md5path)
                        :mode "0666"))
               :user user))
            (is (.canRead target-tmp))
            (is (= "urlmd5urltext" (slurp (.getPath target-tmp)))))
          (testing "delete action"
            (.createNewFile target-tmp)
            (core/lift
             {local node}
             :phase (remote-file (.getPath target-tmp) :action :delete)
             :user user)
            (is (not (.exists target-tmp)))))))))

(deftest transfer-file-to-local-test
  (utils/with-temporary [remote-file (utils/tmpfile)
                         local-file (utils/tmpfile)]
    (let [user (local-test-user)
          local (core/group-spec
                 "local"
                 :phases {:configure (transfer-file-to-local
                                      remote-file local-file)})]
      (io/copy "text" remote-file)
      (testing "with local ssh"
        (let [node (test-utils/make-localhost-node)]
          (testing "with-remote-file"
            (core/lift {local node} :user user)
            (is (= "text" (slurp local-file)))))))))

(def check-content
  (clj-action [session path content path-atom]
    (is (= content (slurp path)))
    (reset! path-atom path)
    [path session]))

(deftest with-remote-file-test
  (core/with-admin-user (local-test-user)
    (utils/with-temporary [remote-file (utils/tmpfile)]
      (let [user (local-test-user)
            local (core/group-spec "local")]
        (io/copy "text" remote-file)
        (testing "with local ssh"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {local node}
               :phase (with-remote-file
                        check-content (.getPath remote-file) "text" path-atom)
               :user user)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))
        (testing "with local shell"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (core/lift
               {local node}
               :phase (with-remote-file
                        check-content (.getPath remote-file) "text" path-atom)
               :user user
               :middleware [core/translate-action-plan])
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))
