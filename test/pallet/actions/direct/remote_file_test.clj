(ns pallet.actions.direct.remote-file-test
  (:use
   [pallet.actions
    :only [exec-script transfer-file transfer-file-to-local remote-file
           with-remote-file]]
   [pallet.actions-impl
    :only [remote-file-action *install-new-files* *force-overwrite*]]
   [pallet.algo.fsmop :only [complete? failed?]]
   [pallet.api :only [group-spec lift plan-fn with-admin-user]]
   [pallet.compute :only [nodes]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.node-value :only [node-value]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils
    :only [clj-action make-localhost-compute make-node test-session
           verify-flag-not-set verify-flag-set]]
   clojure.test)
  (:require
   pallet.actions.direct.remote-file
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :as compute]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
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
  (assoc *admin-user* :username (test-utils/test-username) :no-sudo true))

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
             (->
              (remote-file*
               {} "path"
               {:url "http://a.com/b" :no-versioning true
                :install-new-files true})
              first second)))))
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
             (->
              (remote-file*
               {:environment {:proxy "http://proxy/"}}
               "path" {:url "http://a.com/b" :no-versioning true
                       :install-new-files true})
              first second)))))

  (testing "no-versioning"
    (is (= (stevedore/checked-commands
            "remote-file path"
            (stevedore/script (~lib/heredoc "path.new" "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? "path.new")
               (do
                 (~lib/mv "path.new" path :force true)))))
           (binding [pallet.action-plan/*defining-context* nil]
             (->
              (remote-file* {} "path" {:content "xxx" :no-versioning true
                                       :install-new-files true})
              first second)))))

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
             (->
              (remote-file*
               {} "path" {:content "xxx" :owner "o" :group "g" :mode "m"
                          :no-versioning true :install-new-files true})
              first second)))))

  (testing "delete"
    (is (= (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (binding [pallet.action-plan/*defining-context* nil]
             (->
              (remote-file* {} "path" {:action :delete :force true})
              first second)))))

  (testing "content"
    (utils/with-temporary [tmp (utils/tmpfile)]
      (.delete tmp)
      (is (= (str "remote-file " (.getPath tmp) "...\n"
                  "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                  (.getPath tmp) "\n"
                  "...done\n")
             (let [compute (make-localhost-compute :group-name "local")
                   session @(lift
                             (group-spec "local")
                             :phase (remote-file (.getPath tmp) :content "xxx")
                             :compute compute
                             :user (local-test-user))]
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :result) first :out))))
      (is (= "xxx\n" (slurp (.getPath tmp))))))

  (testing "overwrite on existing content and no md5"
    ;; note that the lift has to run with the same user as the java
    ;; process, otherwise there will be permission errors
    (utils/with-temporary [tmp (utils/tmpfile)]
      (let [compute (make-localhost-compute :group-name "local")
            session @(lift
                      (group-spec "local")
                      :phase (remote-file (.getPath tmp) :content "xxx")
                      :compute compute
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
             (->> session :results (mapcat :result) first :out))
            (is (= "xxx\n" (slurp (.getPath tmp))))))))
  (script/with-script-context [:ubuntu]
    (is (=
         (stevedore/checked-script
          "remote-file path"
          (~lib/heredoc "path.new" "a 1\n" {}))
         (binding [pallet.action-plan/*defining-context* nil]
           (->
            (remote-file*
             (test-session
              {:server {:node (make-node "n" :group-name "n")}}
              {:group {:group-name :n :image {:os-family :ubuntu}}})
             "path"
             {:template "template/strint" :values {'a 1}
              :no-versioning true :install-new-files nil})
            first second))))))

(deftest remote-file-test
  (with-admin-user
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
         (->
          (build-actions/build-actions
           {} (remote-file "file1" :owner "user1"))
          second
          :errors
          first
          str)))

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
        (let [compute (make-localhost-compute :group-name "local")
              local (group-spec "local")]
          (testing "local-file"
            (logging/debugf "local-file is %s" (.getPath tmp))
            (let [result @(lift
                           local
                           :phase (plan-fn
                                    (log-action)
                                    (remote-file
                                     (.getPath target-tmp)
                                     :local-file (.getPath tmp)
                                     :mode "0666"))
                           :compute compute
                           :user user)]
              (is (some
                   #(= (first (nodes compute)) %)
                   (map :node (:targets result)))))
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp))))
            (is (slurp (str (.getPath target-tmp) ".md5")))
            (testing "with md5 guard"
              (logging/info "remote-file test: local-file with md5 guard")
              (let [compute (make-localhost-compute :group-name "local")
                    result @(lift
                             local
                             :phase [(log-action)
                                     (remote-file
                                      (.getPath target-tmp)
                                      :local-file (.getPath tmp)
                                      :mode "0666")]
                             :compute compute
                             :user user)]
                (is (some
                     #(= (first (nodes compute)) %)
                     (map :node (:targets result)))))))
          (testing "content"
            @(lift
              local
              :phase (remote-file (.getPath target-tmp) :content "$(hostname)"
                                  :mode "0666" :flag-on-changed :changed)
              :compute compute
              :user user)
            (is (.canRead target-tmp))
            (is (= (:out (local/local-script "hostname"))
                   (slurp (.getPath target-tmp)))))
          (testing "content unchanged"
            (let [a (atom nil)]
              @(lift
                local
                :compute compute
                :phase (plan-fn
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
              @(lift
                local
                :compute compute
                :phase (plan-fn
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
            @(lift
              local
              :compute compute
              :phase (remote-file
                      (.getPath target-tmp) :content "$text123" :literal true
                      :mode "0666")
              :user user)
            (is (.canRead target-tmp))
            (is (= "$text123\n" (slurp (.getPath target-tmp)))))
          (testing "remote-file"
            (io/copy "text" tmp)
            @(lift
              local
              :compute compute
              :phase (remote-file
                      (.getPath target-tmp) :remote-file (.getPath tmp)
                      :mode "0666")
              :user user)
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp)))))
          (testing "url"
            (io/copy "urltext" tmp)
            @(lift
              local
              :compute compute
              :phase (remote-file
                      (.getPath target-tmp)
                      :url (str "file://" (.getPath tmp))
                      :mode "0666")
              :user user)
            (is (.canRead target-tmp))
            (is (= "urltext" (slurp (.getPath target-tmp)))))
          (testing "url with md5"
            (io/copy "urlmd5text" tmp)
            @(lift
              local
              :compute compute
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
            (let [md5path (str (.getPath tmp) ".md5")
                  op (lift
                      local
                      :compute compute
                      :phase (plan-fn
                               (exec-script
                                ((~lib/md5sum ~(.getPath tmp)) > ~md5path))
                               (remote-file
                                (.getPath target-tmp)
                                :url (str "file://" (.getPath tmp))
                                :md5-url (str "file://" md5path)
                                :mode "0666"))
                      :user user)]
              @op
              (is (complete? op))
              (is (not (failed? op)))
              (is (.canRead target-tmp))
              (is (= "urlmd5urltext" (slurp (.getPath target-tmp))))))
          (testing "delete action"
            (.createNewFile target-tmp)
            @(lift
              local
              :compute compute
              :phase (remote-file (.getPath target-tmp) :action :delete)
              :user user)
            (is (not (.exists target-tmp)))))))))

(deftest transfer-file-to-local-test
  (utils/with-temporary [remote-file (utils/tmpfile)
                         local-file (utils/tmpfile)]
    (let [user (local-test-user)
          local (group-spec
                 "local"
                 :phases {:configure (transfer-file-to-local
                                      remote-file local-file)})
          compute (make-localhost-compute :group-name "local")]
      (io/copy "text" remote-file)
      (testing "with local ssh"
        (let [node (test-utils/make-localhost-node)]
          (testing "with-remote-file"
            @(lift local :compute compute :user user)
            (is (= "text" (slurp local-file)))))))))

(def check-content
  (clj-action [session path content path-atom]
    (is (= content (slurp path)))
    (reset! path-atom path)
    [path session]))

(deftest with-remote-file-test
  (with-admin-user (local-test-user)
    (utils/with-temporary [remote-file (utils/tmpfile)]
      (let [user (local-test-user)
            local (group-spec "local")
            compute (make-localhost-compute :group-name "local")]
        (io/copy "text" remote-file)
        (testing "with local ssh"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              @(lift
                local
                :compute compute
               :phase (with-remote-file
                        check-content (.getPath remote-file) "text" path-atom)
               :user user)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))
        (testing "with local shell"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              @(lift
                local
                :compute compute
               :phase (with-remote-file
                        check-content (.getPath remote-file) "text" path-atom)
               :user user
               ;; :middleware [translate-action-plan]
               )
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))
