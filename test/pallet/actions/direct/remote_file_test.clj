(ns pallet.actions.direct.remote-file-test
  (:require
   [clojure.java.io :as io]
   [clojure.stacktrace :refer [print-cause-trace print-stack-trace root-cause]]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions
    :refer [exec-script
            remote-file
            remote-file-content
            transfer-file-to-local
            with-remote-file]]
   [pallet.actions-impl :refer [copy-filename md5-filename new-filename]]
   [pallet.actions.decl :refer [remote-file-action]]
   [pallet.actions.direct.remote-file :refer [create-path-with-template]]
   [pallet.api :refer [group-spec lift plan-fn with-admin-user]]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils :refer [with-log-to-string]]
   [pallet.compute :refer [nodes]]
   [pallet.contracts :refer [*verify-contracts*]]
   [pallet.core.api :as core-api :refer [phase-errors]]
   [pallet.core.session :refer [with-session]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.local.execute :as local]
   [pallet.script :as script]
   [pallet.script.lib :as lib :refer [user-home]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-executors :as test-executors]
   [pallet.test-utils :as test-utils]
   [pallet.test-utils
    :refer [make-localhost-compute
            make-node
            test-session
            verify-flag-not-set
            verify-flag-set]]
   [pallet.utils :as utils]
   [pallet.utils :refer [tmpdir with-temporary]]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 (logutils/logging-threshold-fixture))

(def remote-file* (action/action-fn remote-file-action :direct))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-utils/test-username) :no-sudo true))

(deftest create-path-with-template-test
  (is (pallet.actions.direct.remote-file/create-path-with-template
       "a/b/c/d" "/c/d")))

(deftest remote-file*-test
  (is remote-file*)
  (with-session {:environment {:user {:username "fred"}}}
    (testing "url"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             ~(create-path-with-template
               "path"
               (str
                "/var/lib/pallet" "/home/fred" "/path.new"))
             (lib/download-file "http://a.com/b" (new-filename nil "path"))
             (if (file-exists? (new-filename nil "path"))
               (do
                 (lib/cp
                  (new-filename nil "path")
                  (copy-filename nil "path")
                  :force true)
                 (lib/mv (new-filename nil "path") path :force true)))))
           (->
            (remote-file*
             {} "path"
             {:url "http://a.com/b" :no-versioning true
              :install-new-files true})
            first second))))
    (testing "url with proxy"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (stevedore/chained-script
             ~(create-path-with-template
               "path"
               (str
                "/var/lib/pallet" "/home/fred" "/path.new"))
             (lib/download-file
              "http://a.com/b" (new-filename nil "path") :proxy "http://proxy/")
             (if (file-exists? (new-filename nil "path"))
               (do
                 (lib/cp
                  (new-filename nil "path")
                  (copy-filename nil "path")
                  :force true)
                 (lib/mv (new-filename nil "path") path :force true)))))
           (->
            (remote-file*
             {:environment {:proxy "http://proxy/"}}
             "path" {:url "http://a.com/b" :no-versioning true
                     :install-new-files true})
            first second))))

    (testing "no-versioning"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (create-path-with-template
             "path"
             (str "/var/lib/pallet" "/home/fred" "/path.new"))
            (stevedore/script (~lib/heredoc (new-filename nil "path") "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? (new-filename nil "path"))
               (do
                 (lib/cp
                  (new-filename nil "path")
                  (copy-filename nil "path")
                  :force true)
                 (lib/mv (new-filename nil "path") path :force true)))))
           (->
            (remote-file* {} "path" {:content "xxx" :no-versioning true
                                     :install-new-files true})
            first second))))

    (testing "no-versioning with owner, group and mode"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (create-path-with-template
             "path"
             (str "/var/lib/pallet" "/home/fred" "/path.new"))
            (stevedore/script (~lib/heredoc (new-filename nil "path") "xxx" {}))
            (stevedore/chained-script
             (if (file-exists? (new-filename nil "path"))
               (do
                 (lib/cp
                  (new-filename nil "path")
                  (copy-filename nil "path")
                  :force true)
                 (lib/mv (new-filename nil "path") "path" :force true)))
             (~lib/chown "o" "path")
             (~lib/chgrp "g" "path")
             (~lib/chmod "m" "path")))
           (->
            (remote-file*
             {} "path" {:content "xxx" :owner "o" :group "g" :mode "m"
                        :no-versioning true :install-new-files true})
            first second))))

    (testing "delete"
      (is (script-no-comment=
           (stevedore/checked-script
            "delete remote-file path"
            ("rm" "--force" "path"))
           (->
            (remote-file* {} "path" {:action :delete :force true})
            first second))))
    (script/with-script-context [:ubuntu]
      (is (script-no-comment=
           (stevedore/checked-script
            "remote-file path"
            ~(create-path-with-template
              "path"
              (str "/var/lib/pallet" "/home/fred" "/path.new"))
            (lib/heredoc (new-filename nil "path") "a 1\n" {}))
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
    (testing "content"
      (utils/with-temporary [tmp (utils/tmpfile)]
        (.delete tmp)
        (is (script-no-comment=
             (str "remote-file " (.getPath tmp) "...\n"
                  "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                  (.getName tmp) "\n"
                  "#> remote-file " (.getPath tmp) " : SUCCESS")
             (let [compute (make-localhost-compute :group-name "local")
                   op (lift
                       (group-spec "local")
                       :phase (plan-fn
                               (remote-file
                                (.getPath tmp) :content "xxx"))
                       :compute compute
                       :user (local-test-user)
                       :async true)
                   session @op]
               (is (not (phase-errors @op)))
               (is (nil? (phase-errors @op)))
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :result) first :out))))
        (is (script-no-comment=
             "xxx\n" (slurp (.getPath tmp))))))
    (testing "overwrite on existing content and no md5"
      ;; note that the lift has to run with the same user as the java
      ;; process, otherwise there will be permission errors
      (utils/with-temporary [tmp (utils/tmpfile)]
        (let [compute (make-localhost-compute :group-name "local")
              op (lift
                  (group-spec "local")
                  :phase (plan-fn
                          (remote-file (.getPath tmp) :content "xxx"))
                  :compute compute
                  :user (local-test-user)
                  :executor test-executors/test-executor
                  :async true)
              session @op]
          (logging/infof
           "r-f-t overwrite on existing content and no md5: session %s"
           session)
          (is (not (phase-errors @op)))
          (is (not (seq (phase-errors @op))))
          (is (re-matches #"(?sm)remote-file .*SUCCESS\n"
                          (->> session :results (mapcat :result) first :out))
              (is (= "xxx\n" (slurp (.getPath tmp))))))))
    (testing "non-existant local-file"
      (is (thrown-with-msg? RuntimeException
                            #".*/some/non-existing/file.*does not exist, is a directory, or is unreadable.*"
                            (build-actions/build-actions
                                {} (remote-file
                                    "file1" :local-file "/some/non-existing/file"
                                    :owner "user1")))))
    (testing "no content specified"
      (with-log-to-string []
        (is (thrown-with-msg?
             Exception #".*Constraint failed.*"
             (->
              (build-actions/build-actions
                  {} (remote-file "file1" :owner "user1")))))))
    ;; (testing "no content specified (no verification)"
    ;;   (with-log-to-string []
    ;;     (is (=
    ;;          (str
    ;;           "{:error {:type :pallet/action-execution-error, "
    ;;           ":context nil, "
    ;;           ":message \"Unexpected exception: "
    ;;           "remote-file file1 specified without content.\", :cause "
    ;;           "#<IllegalArgumentException java.lang.IllegalArgumentException: "
    ;;           "remote-file file1 specified without content.>}}")
    ;;          (binding [*verify-contracts* false]
    ;;            (->
    ;;             (build-actions/build-actions
    ;;                 {} (remote-file "file1" :owner "user1"))
    ;;             second
    ;;             build-actions/action-phase-errors
    ;;             first
    ;;             str))))))

    (testing "local-file script"
      (utils/with-temporary [tmp (utils/tmpfile)]
        (let [s (first
                 (build-actions/build-actions {}
                   (remote-file "file1" :local-file (.getPath tmp))))]
          (is
           (re-find
            #"cp -f --backup=\"numbered\" .*pallet.*file1.new .*pallet.*file1"
            s))
          (is (re-find #"mv -f .*pallet.*file1.new file1" s)))))

    (testing "local-file"
      (utils/with-temporary [tmp (utils/tmpfile)
                             target-tmp (utils/tmpfile)]
        ;; this is convoluted to get around the "t" sticky bit on temp dirs
        (let [user (local-test-user)]
          (.delete target-tmp)
          (io/copy "text" tmp)
          (let [compute (make-localhost-compute :group-name "local")
                local (group-spec "local")]
            (testing "local-file"
              (logging/debugf "local-file is %s" (.getPath tmp))
              (let [op (lift
                        local
                        :phase (plan-fn
                                (remote-file
                                 (.getPath target-tmp)
                                 :local-file (.getPath tmp)
                                 :mode "0666"))
                        :compute compute
                        :user user
                        :async true)
                    result (deref op)]
                (is (nil? (:exception @op)))
                (is (nil? (phase-errors @op)))
                (is (some
                     #(= (first (nodes compute)) %)
                     (map :node (:targets result)))))
              (is (.canRead target-tmp))
              (is (= "text" (slurp (.getPath target-tmp))))
              (is (slurp (md5-filename nil (.getPath target-tmp))))
              (testing "with md5 guard same content"
                (logging/info "remote-file test: local-file with md5 guard")
                (let [compute (make-localhost-compute :group-name "local")
                      op (lift
                          local
                          :phase (plan-fn
                                  (remote-file
                                   (.getPath target-tmp)
                                   :local-file (.getPath tmp)
                                   :mode "0666"))
                          :compute compute
                          :user user
                          :async true)
                      result (deref op)]
                  (is (nil? (phase-errors @op)))
                  (is (nil? (:exception @op)))
                  (is (some
                       #(= (first (nodes compute)) %)
                       (map :node (:targets result))))))
              (testing "with md5 guard different content"
                (logging/info "remote-file test: local-file with md5 guard")
                (io/copy "text2" tmp)
                (let [compute (make-localhost-compute :group-name "local")
                      op (lift
                          local
                          :phase (plan-fn
                                  (remote-file
                                   (.getPath target-tmp)
                                   :local-file (.getPath tmp)
                                   :mode "0666"))
                          :compute compute
                          :user user
                          :async true)
                      result (deref op)]
                  (is (nil? (phase-errors @op)))
                  (is (nil? (:exception @op)))
                  (is (some
                       #(= (first (nodes compute)) %)
                       (map :node (:targets result)))))))
            (testing "content"
              (let [op (lift
                        local
                        :phase (plan-fn
                                (remote-file (.getPath target-tmp)
                                             :content "$(hostname)"
                                             :mode "0666"
                                             :flag-on-changed "changed"))
                        :compute compute
                        :user user
                        :async true)
                    result @op]
                (is (nil? (phase-errors @op)))
                (is (nil? (:exception @op)))
                (is (.canRead target-tmp))
                (is (= (:out (local/local-script "hostname"))
                       (slurp (.getPath target-tmp))))))
            (testing "content unchanged"
              (let [a (atom nil)]
                (lift
                 local
                 :compute compute
                 :phase (plan-fn
                         (let [nv (remote-file
                                   (.getPath target-tmp)
                                   :content "$(hostname)"
                                   :mode "0666" :flag-on-changed "changed")]
                           (verify-flag-not-set :changed)
                           (reset! a true)
                           (is (nil? (seq (:flags nv))))))
                 :user user)
                (is @a)
                (is (.canRead target-tmp))
                (is (= (:out (local/local-script "hostname"))
                       (slurp (.getPath target-tmp))))))
            (testing "content changed"
              (let [flag-verified (atom nil)
                    op (lift
                        local
                        :compute compute
                        :phase (plan-fn
                                (let [nv (remote-file
                                          (.getPath target-tmp) :content "abc"
                                          :mode "0666" :flag-on-changed "changed")]
                                  (verify-flag-set :changed)
                                  (reset! flag-verified true)
                                  (is (:flags nv))
                                  (is ((:flags nv) :changed))))
                        :user user)]
                (is (not (phase-errors op)))
                (is @flag-verified))
              (is (.canRead target-tmp))
              (is (= "abc\n"
                     (slurp (.getPath target-tmp)))))
            (testing "content"
              (lift
               local
               :compute compute
               :phase (plan-fn
                       (remote-file
                        (.getPath target-tmp)
                        :content "$text123" :literal true
                        :mode "0666"))
               :user user)
              (is (.canRead target-tmp))
              (is (= "$text123\n" (slurp (.getPath target-tmp)))))
            (testing "remote-file"
              (io/copy "text" tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn
                       (remote-file
                        (.getPath target-tmp) :remote-file (.getPath tmp)
                        :mode "0666"))
               :user user)
              (is (.canRead target-tmp))
              (is (= "text" (slurp (.getPath target-tmp)))))
            (testing "url"
              (io/copy "urltext" tmp)
              (let [op (lift
                        local
                        :compute compute
                        :phase (plan-fn
                                (remote-file
                                 (.getPath target-tmp)
                                 :url (str "file://" (.getPath tmp))
                                 :mode "0666"))
                        :user user
                        :async true)]
                (is @op)
                (is (nil? (phase-errors @op)))
                (is (.canRead target-tmp))
                (is (= "urltext" (slurp (.getPath target-tmp))))))
            (testing "url with md5"
              (io/copy "urlmd5text" tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn
                       (remote-file
                        (.getPath target-tmp)
                        :url (str "file://" (.getPath tmp))
                        :md5 (stevedore/script @(~lib/md5sum ~(.getPath tmp)))
                        :mode "0666"))
               :user user)
              (is (.canRead target-tmp))
              (is (= "urlmd5text" (slurp (.getPath target-tmp)))))
            (testing "url with md5 urls"
              (with-temporary [tmp-dir (tmpdir)
                               tmp-copy (io/file tmp-dir (.getName target-tmp))
                               tmp-md5 (io/file
                                        tmp-dir
                                        (str (.getName target-tmp) ".md5"))]
                (.delete target-tmp)
                (io/copy "urlmd5urltext" tmp)
                (io/copy tmp tmp-copy)
                (let [md5path (.getPath tmp-md5)
                      op (lift
                          local
                          :compute compute
                          :phase (plan-fn
                                  ;; create md5 file to download
                                  (exec-script
                                   ((lib/md5sum ~(.getPath tmp-copy))
                                    > ~md5path))
                                  (remote-file
                                   (.getPath target-tmp)
                                   :url (str "file://" (.getPath tmp))
                                   :md5-url (str "file://" md5path)
                                   :mode "0666"))
                          :user user
                          :async true)]
                  @op
                  (is (nil? (phase-errors @op)))
                  (is (nil? (:exception @op)))
                  (is (.canRead target-tmp))
                  (is (= "urlmd5urltext" (slurp (.getPath target-tmp)))))))
            (testing "delete action"
              (.createNewFile target-tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn
                       (remote-file (.getPath target-tmp) :action :delete))
               :user user)
              (is (not (.exists target-tmp))))))))
    (testing "with :script-dir"
      (utils/with-temporary [tmp (utils/tmpfile)]
        (.delete tmp)
        (is (script-no-comment=
             (str "remote-file " (.getName tmp) "...\n"
                  "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
                  (.getName tmp) "\n"
                  "#> remote-file " (.getName tmp) " : SUCCESS")
             (let [compute (make-localhost-compute :group-name "local")
                   op (lift
                       (group-spec "local")
                       :phase (plan-fn
                               (with-action-options
                                 {:script-dir (.getParent tmp)}
                                 (remote-file (.getName tmp) :content "xxx")))
                       :compute compute
                       :user (local-test-user)
                       :async true)
                   session @op]
               (is (not (phase-errors @op)))
               (is (nil? (phase-errors @op)))
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :result) first :out))))
        (is (= "xxx\n" (slurp (.getPath tmp))))))))

(deftest transfer-file-to-local-test
  (utils/with-temporary [remote-file (utils/tmpfile)
                         local-file (utils/tmpfile)]
    (let [user (local-test-user)
          local (group-spec
                 "local"
                 :phases {:configure (plan-fn
                                       (transfer-file-to-local
                                        remote-file local-file))})
          compute (make-localhost-compute :group-name "local")]
      (io/copy "text" remote-file)
      (testing "with local ssh"
        (let [node (test-utils/make-localhost-node)]
          (testing "with-remote-file"
            (lift local :compute compute :user user)
            (is (= "text" (slurp local-file)))))))))

(defn check-content
  [path content path-atom]
  (is (= content (slurp path)))
  (reset! path-atom path))

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
              (lift
               local
               :compute compute
               :phase (plan-fn
                        (with-remote-file
                          check-content (.getPath remote-file) "text" path-atom))
               :user user)
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))
        (testing "with local shell"
          (let [node (test-utils/make-localhost-node)
                path-atom (atom nil)]
            (testing "with-remote-file"
              (lift
               local
               :compute compute
               :phase (plan-fn
                        (with-remote-file
                          check-content
                          (.getPath remote-file) "text" path-atom))
               :user user
               ;; :middleware [translate-action-plan]
               )
              (is @path-atom)
              (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))

(deftest remote-file-content-test
  (with-admin-user (local-test-user)
    (utils/with-temporary [tmp-file (utils/tmpfile)
                           tmp-file-2 (utils/tmpfile)]
      (let [user (local-test-user)
            local (group-spec "local")
            compute (make-localhost-compute :group-name "local")]
        (testing "with local ssh"
          (let [node (test-utils/make-localhost-node)]
            (testing "remote-file-content with explicit node-value"
              (io/copy "text" tmp-file)
              (let [seen (atom nil)
                    result
                    (lift
                     local
                     :compute compute
                     :phase
                     (plan-fn
                       (let [content (remote-file-content (.getPath tmp-file))
                             is-text (= content "text")]
                         (when is-text
                           (let [new-content (string/replace content "x" "s")]
                             (reset! seen true)
                             (remote-file
                              (.getPath tmp-file-2)
                              :content new-content)))))
                     :user user
                     :async true)]
                @result
                (is (not (phase-errors @result)))
                (is (nil? (phase-errors @result)))
                (when (phase-errors @result)
                  (when-let [e (:exception @result)]
                    (print-stack-trace (root-cause e))))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))
            (testing "remote-file-content with deref"
              (io/copy "text" tmp-file)
              (let [seen (atom nil)
                    result
                    (lift
                     local
                     :compute compute
                     :phase
                     (plan-fn
                       (let [content (remote-file-content (.getPath tmp-file))]
                         (when (= content "text")
                           (let [new-content (string/replace content "x" "s")]
                             (reset! seen true)
                             (remote-file
                              (.getPath tmp-file-2)
                              :content new-content)))))
                     :user user
                     :async true)]
                @result
                (is (not (phase-errors @result)))
                (is (nil? (phase-errors @result)))
                (if (phase-errors @result)
                  (when-let [e (:exception @result)]
                    (print-cause-trace e)))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))
            (testing "remote-file-content with deref and eval'd args"
              (io/copy "text" tmp-file)
              (.delete tmp-file-2)
              (let [seen (atom nil)
                    result
                    (lift
                     local
                     :compute compute
                     :phase
                     (plan-fn
                       (let [content (remote-file-content (.getPath tmp-file))]
                         (when (= content "text")
                           (reset! seen true)
                           (remote-file-action
                            (.getPath tmp-file-2)
                            {:content (string/replace content "x" "s")}))))
                     :user user
                     :async true)]
                (is @result)
                (is (nil? (phase-errors @result)))
                (when (phase-errors @result)
                  (when-let [e (:exception @result)]
                    (print-cause-trace e)))
                (is (not (phase-errors @result)))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))
            (testing "remote-file-content with delayed to non-action"
              (io/copy "text" tmp-file)
              (.delete tmp-file-2)
              (let [seen (atom nil)
                    result
                    (lift
                     local
                     :compute compute
                     :phase
                     (plan-fn
                       (let [content (remote-file-content (.getPath tmp-file))]
                         (when (= content "text")
                           (reset! seen true)
                           (remote-file
                            (.getPath tmp-file-2)
                            :content (string/replace content "x" "s")))))
                     :user user
                     :async true)]
                (is @result)
                (is (not (phase-errors @result)))
                (is (nil? (phase-errors @result)))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))
            (testing "remote-file-content with non-action"
              (io/copy "text" tmp-file)
              (.delete tmp-file-2)
              (let [seen (atom nil)
                    result
                    (lift
                     local
                     :compute compute
                     :phase
                     (plan-fn
                       (let [content (remote-file-content (.getPath tmp-file))]
                         (when (= content "text")
                           (reset! seen true)
                           (remote-file
                            (.getPath tmp-file-2)
                            :content (string/replace content "x" "s")))))
                     :user user
                     :async true)]
                (is @result)
                (is (not (phase-errors @result)))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))))))))
