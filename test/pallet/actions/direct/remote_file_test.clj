(ns pallet.actions.direct.remote-file-test
  (:require
   [clojure.java.io :as io]
   [clojure.stacktrace :refer [print-cause-trace print-stack-trace root-cause]]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.actions
    :refer [exec-script
            plan-when
            remote-file
            remote-file-content
            transfer-file-to-local
            with-action-values
            with-remote-file]]
   [pallet.actions-impl
    :refer [copy-filename md5-filename new-filename remote-file-action]]
   [pallet.algo.fsmop :refer [complete? failed? wait-for]]
   [pallet.api :refer [group-spec lift plan-fn with-admin-user]]
   [pallet.argument :refer [delayed]]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :as logutils :refer [with-log-to-string]]
   [pallet.compute :refer [nodes]]
   [pallet.contracts :refer [*verify-contracts*]]
   [pallet.core.api :as core-api]
   [pallet.core.file-upload.rsync-upload :refer [rsync-upload]]
   [pallet.core.primitives :refer [phase-errors]]
   [pallet.core.session :refer [session with-session]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.local.execute :as local]
   [pallet.script :as script]
   [pallet.script.lib :as lib :refer [user-home]]
   [pallet.ssh.node-state :refer [verify-checksum]]
   [pallet.ssh.node-state.state-root :refer [create-path-with-template]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-executors :as test-executors]
   [pallet.test-utils :as test-utils]
   [pallet.test-utils
    :refer [clj-action
            make-localhost-compute
            make-node
            test-session
            verify-flag-not-set
            verify-flag-set]]
   [pallet.utils :as utils :refer [deep-merge tmpdir with-temporary]]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 (logutils/logging-threshold-fixture))

(def remote-file* (action/action-fn remote-file-action :direct))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-utils/test-username) :no-sudo true))

;; (deftest remote-file*-test
;;   (is remote-file*)
;;   (with-session {:environment {:user {:username "fred"}}}
;;     (let [upload-path ()])
;;     (testing "url"
;;       (is (script-no-comment=
;;            (stevedore/checked-commands
;;             "remote-file path"
;;             (verify-checksum default-node-state (session) "path")
;;             (stevedore/chained-script

;;              (lib/download-file "http://a.com/b"
;;                                 ~(new-filename (session) nil "path"))
;;              (if (file-exists? ~(new-filename (session) nil "path"))
;;                (do
;;                  (lib/cp
;;                   ~(new-filename (session) nil "path")
;;                   ~(copy-filename (session) nil "path")
;;                   :force true)
;;                  (lib/mv ~(new-filename (session) nil "path") path
;;                          :force true))
;;                ~(create-path-with-template
;;                  "path"
;;                  (str
;;                   "/var/lib/pallet" "/home/fred" "/path.new")))))
;;            (binding [pallet.action-plan/*defining-context* nil]
;;              (->
;;               (remote-file*
;;                (session) "path"
;;                {:url "http://a.com/b" :no-versioning true
;;                 :install-new-files true})
;;               first second)))))
;;     (testing "url with proxy"
;;       (is (script-no-comment=
;;            (stevedore/checked-commands
;;             "remote-file path"
;;             (stevedore/chained-script
;;              ~(create-path-with-template
;;                "path"
;;                (str
;;                 "/var/lib/pallet" "/home/fred" "/path.new"))
;;              (lib/download-file
;;               "http://a.com/b" ~(new-filename (session) nil "path")
;;               :proxy "http://proxy/")
;;              (if (file-exists? ~(new-filename (session) nil "path"))
;;                (do
;;                  (lib/cp
;;                   ~(new-filename (session) nil "path")
;;                   ~(copy-filename (session) nil "path")
;;                   :force true)
;;                  (lib/mv ~(new-filename (session) nil "path") path
;;                          :force true)))))
;;            (binding [pallet.action-plan/*defining-context* nil]
;;              (->
;;               (remote-file*
;;                (deep-merge (session) {:environment {:proxy "http://proxy/"}})
;;                "path" {:url "http://a.com/b" :no-versioning true
;;                        :install-new-files true})
;;               first second)))))

;;     (testing "no-versioning"
;;       (let [session (session)
;;             nf (new-filename session nil "path")
;;             cf (copy-filename session nil "path")]
;;         (is (script-no-comment=
;;              (stevedore/checked-commands
;;               "remote-file path"
;;               (create-path-with-template
;;                "path"
;;                (str "/var/lib/pallet" "/home/fred" "/path.new"))
;;               (stevedore/script
;;                (lib/heredoc ~nf "xxx" {}))
;;               (stevedore/chained-script
;;                (if (file-exists? ~nf)
;;                  (do
;;                    (lib/cp ~nf ~cf :force true)
;;                    (lib/mv ~nf path :force true)))))
;;              (binding [pallet.action-plan/*defining-context* nil]
;;                (->
;;                 (remote-file*
;;                  session
;;                  "path" {:content "xxx" :no-versioning true
;;                          :install-new-files true})
;;                 first second))))))

;;     (testing "no-versioning with owner, group and mode"
;;       (let [session (session)
;;             nf (new-filename session nil "path")
;;             cf (copy-filename session nil "path")]
;;         (is (script-no-comment=
;;              (stevedore/checked-commands
;;               "remote-file path"
;;               (create-path-with-template
;;                "path"
;;                (str "/var/lib/pallet" "/home/fred" "/path.new"))
;;               (stevedore/script
;;                (lib/heredoc ~nf "xxx" {}))
;;               (stevedore/chained-script
;;                (if (file-exists? ~nf)
;;                  (do
;;                    (lib/cp ~nf ~cf :force true)
;;                    (lib/mv ~nf "path" :force true)))
;;                (lib/chown "o" "path")
;;                (lib/chgrp "g" "path")
;;                (lib/chmod "m" "path")))
;;              (binding [pallet.action-plan/*defining-context* nil]
;;                (->
;;                 (remote-file*
;;                  session "path"
;;                  {:content "xxx" :owner "o" :group "g" :mode "m"
;;                   :no-versioning true :install-new-files true})
;;                 first second))))))

;;     (testing "delete"
;;       (is (script-no-comment=
;;            (stevedore/checked-script
;;             "delete remote-file path"
;;             ("rm" "--force" "path"))
;;            (binding [pallet.action-plan/*defining-context* nil]
;;              (->
;;               (remote-file* (session) "path" {:action :delete :force true})
;;               first second)))))
;;     (script/with-script-context [:ubuntu]
;;       (is (script-no-comment=
;;            (stevedore/checked-script
;;             "remote-file path"
;;             ~(create-path-with-template
;;               "path"
;;               (str "/var/lib/pallet" "/home/fred" "/path.new"))
;;             (lib/heredoc ~(new-filename (session) nil "path") "a 1\n" {}))
;;            (binding [pallet.action-plan/*defining-context* nil]
;;              (->
;;               (remote-file*
;;                (test-session
;;                 (session)
;;                 {:server {:node (make-node "n" :group-name "n")}}
;;                 {:group {:group-name :n :image {:os-family :ubuntu}}})
;;                "path"
;;                {:template "template/strint" :values {'a 1}
;;                 :no-versioning true :install-new-files nil})
;;               first second)))))))

;; (testing "local-file script"
;;       (utils/with-temporary [tmp (utils/tmpfile)]
;;         (let [s (first
;;                  (build-actions/build-actions
;;                      {} (remote-file
;;                          "file1" :local-file (.getPath tmp))))]
;;           (is
;;            (re-find
;;             #"cp -f --backup=\"numbered\" file1 /var/lib/pallet$(pwd)/file1"
;;             s))
;;           (is (re-find #"mv -f /tmp/.* file1" s)))))

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
                               (remote-file (.getPath tmp) :content "xxx"))
                       :compute compute
                       :user (local-test-user)
                       :async true)
                   session @op]
               (is (not (failed? op)) "op completed")
               (is (nil? (phase-errors op)) "no phase-errors")
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :result) first :out)))
            "generated a checksum")
        (is (= "xxx\n" (slurp (.getPath tmp))) "wrote the content")))
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
          (is (not (failed? op)))
          (is (not (seq (phase-errors op))))
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
    (testing "no content specified (no verification)"
      (with-log-to-string []
        (is (=
             (str
              "{:error {:type :pallet/action-execution-error, "
              ":context nil, "
              ":message \"Unexpected exception: "
              "remote-file file1 specified without content.\", :cause "
              "#<IllegalArgumentException java.lang.IllegalArgumentException: "
              "remote-file file1 specified without content.>}}")
             (binding [*verify-contracts* false]
               (->
                (build-actions/build-actions
                    {} (remote-file "file1" :owner "user1"))
                second
                build-actions/action-phase-errors
                first
                str))))))
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
                    result (wait-for op)]
                (is (complete? op))
                (is (nil? (:exception @op)))
                (is (nil? (phase-errors op)))
                (is (some
                     #(= (first (nodes compute)) %)
                     (map :node (:targets result)))))
              (is (.canRead target-tmp))
              (is (= "text" (slurp (.getPath target-tmp))))
              (is (slurp (md5-filename {:environment {:user {}}}
                                       nil (.getPath target-tmp))))
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
                      result (wait-for op)]
                  (is (nil? (phase-errors op)))
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
                      result (wait-for op)]
                  (is (nil? (phase-errors op)))
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
                (is (nil? (phase-errors op)))
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
                            ((clj-action
                               [session nv]
                               (reset! a true)
                               (is (nil? (seq (:flags nv))))
                               [nil session])
                             nv)))
                 :user user)
                (is @a)
                (is (.canRead target-tmp))
                (is (= (:out (local/local-script "hostname"))
                       (slurp (.getPath target-tmp))))))
            (testing "content changed"
              (let [a (atom nil)]
                (lift
                 local
                 :compute compute
                 :phase (plan-fn
                          (let [nv (remote-file
                                    (.getPath target-tmp) :content "abc"
                                    :mode "0666" :flag-on-changed "changed")]
                            (verify-flag-set :changed)
                            ((clj-action
                               [session nv]
                               (reset! a true)
                               (is (:flags nv))
                               (is ((:flags nv) :changed))
                               [nil session])
                             nv)))
                 :user user)
                (is @a))
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
                (is (nil? (phase-errors op)))
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
                  (is (nil? (phase-errors op)))
                  (is (nil? (:exception @op)))
                  (is (complete? op))
                  (is (not (failed? op)))
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
                                (action/with-action-options
                                  {:script-dir (.getParent tmp)}
                                  (remote-file (.getName tmp) :content "xxx")))
                       :compute compute
                       :user (local-test-user)
                       :async true)
                   session @op]
               (is (not (failed? op)))
               (is (nil? (phase-errors op)))
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :result) first :out))))
        (is (= "xxx\n" (slurp (.getPath tmp))))))))

(deftest rsync-file-upload-test
  (testing "local-file via rsync"
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
                      :environment {:action-options
                                    {:file-uploader (rsync-upload {})}}
                      :compute compute
                      :user user
                      :async true)
                  result (wait-for op)]
              (is (complete? op))
              (is (nil? (:exception @op)))
              (is (nil? (phase-errors op)))
              (is (some
                   #(= (first (nodes compute)) %)
                   (map :node (:targets result)))))
            (is (.canRead target-tmp))
            (is (= "text" (slurp (.getPath target-tmp))))))))))

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
              (lift
               local
               :compute compute
               :phase (plan-fn
                        (with-remote-file
                          check-content
                          (.getPath remote-file) "text" path-atom))
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
                             is-text (with-action-values [content]
                                       (= content "text"))]
                         (plan-when @is-text
                           (let [new-content (with-action-values [content]
                                               (string/replace
                                                content "x" "s"))]
                             (reset! seen true)
                             (remote-file
                              (.getPath tmp-file-2)
                              :content (delayed [_] @new-content))))))
                     :user user
                     :async true)]
                @result
                (is (not (failed? result)))
                (is (nil? (phase-errors result)))
                (when (failed? result)
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
                         (plan-when (= @content "text")
                           (let [new-content (with-action-values [content]
                                               (string/replace
                                                content "x" "s"))]
                             (reset! seen true)
                             (remote-file
                              (.getPath tmp-file-2)
                              :content (delayed [_] @new-content))))))
                     :user user
                     :async true)]
                @result
                (is (not (failed? result)))
                (is (nil? (phase-errors result)))
                (if (failed? result)
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
                         (plan-when (= @content "text")
                           (reset! seen true)
                           (remote-file-action
                            (.getPath tmp-file-2)
                            (delayed [_]
                              {:content (string/replace @content "x" "s")})))))
                     :user user
                     :async true)]
                (is @result)
                (is (nil? (phase-errors result)))
                (when (failed? result)
                  (when-let [e (:exception @result)]
                    (print-cause-trace e)))
                (is (not (failed? result)))
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
                         (plan-when (= @content "text")
                           (reset! seen true)
                           (remote-file
                            (.getPath tmp-file-2)
                            :content (delayed [s]
                                       (string/replace @content "x" "s"))))))
                     :user user
                     :async true)]
                (is @result)
                (is (not (failed? result)))
                (is (nil? (phase-errors result)))
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
                         (plan-when (= @content "text")
                           (reset! seen true)
                           (remote-file
                            (.getPath tmp-file-2)
                            :content (delayed [_]
                                       (string/replace @content "x" "s"))))))
                     :user user
                     :async true)]
                (is @result)
                (is (not (failed? result)))
                (is @seen)
                (is (= (slurp (.getPath tmp-file-2)) "test\n"))
                (flush)))))))))
