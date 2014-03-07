(ns pallet.ssh.actions.remote-file-test
  (:require
   [clojure.java.io :as io]
   [clojure.stacktrace :refer [print-cause-trace print-stack-trace root-cause]]
   [clojure.string :as string]
   [clojure.test :refer :all]
   [clojure.tools.logging :as logging]
   [pallet.action :as action]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions
    :refer [exec-checked-script
            exec-script
            exec-script*
            remote-file
            remote-file-content
            with-remote-file]]
   [pallet.actions.decl :refer [transfer-file-to-local]]
   [pallet.actions.direct.remote-file
    :refer [default-checksum default-backup default-file-uploader]]
   [pallet.build-actions :as build-actions :refer [build-script]]
   [pallet.common.logging.logutils
    :refer [logging-threshold-fixture with-log-to-string]]
   [pallet.compute :refer [nodes]]
   [pallet.core.file-upload :refer [upload-file-path]]
   [pallet.core.file-upload.rsync-upload :refer [rsync-upload]]
   [pallet.group :refer [group-spec lift phase-errors]]
   [pallet.local.execute :as local]
   [pallet.plan :refer [plan-fn]]
   [pallet.script :as script]
   [pallet.script.lib :as lib :refer [user-home]]
   [pallet.ssh.node-state :refer [verify-checksum]]
   [pallet.ssh.node-state.no-state :refer [no-backup no-checksum]]
   [pallet.ssh.node-state.state-root :refer [create-path-with-template]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils :as test-utils]
   [pallet.test-utils
    :refer [make-localhost-compute
            make-node
            target-node-state
            test-username]]
   [pallet.user :refer [*admin-user* with-admin-user]]
   [pallet.utils :as utils]
   [pallet.utils :refer [tmpdir with-temporary]]))


(use-fixtures :once
  (logging-threshold-fixture))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

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
                   session (lift
                            (group-spec "local" {})
                            :phase (plan-fn [session]
                                     (remote-file
                                      session (.getPath tmp) {:content "xxx"}))
                            :compute compute
                            :user (local-test-user))]
               (is (not (phase-errors session)))
               (is (nil? (phase-errors session)))
               (logging/infof "r-f-t content: session %s" session)
               (->> session :results (mapcat :action-results) last :out)))
            "generated a checksum")
        (is (= "xxx\n" (slurp (.getPath tmp))) "wrote the content")))

    (testing "overwrite on existing content and no md5"
      ;; note that the lift has to run with the same user as the java
      ;; process, otherwise there will be permission errors
      (utils/with-temporary [tmp (utils/tmpfile)]
        (let [compute (make-localhost-compute :group-name "local")
              session (lift
                       (group-spec "local" {})
                       :phase (plan-fn [session]
                                (remote-file
                                 session (.getPath tmp) {:content "xxx"}))
                       :compute compute
                       :user (local-test-user))]
          (logging/infof
           "r-f-t overwrite on existing content and no md5: session %s"
           session)
          (is (not (phase-errors session)))
          (is (not (seq (phase-errors session))))
          (is (re-matches
               #"(?sm)remote-file .*SUCCESS\n"
               (->> session :results (mapcat :action-results) last :out)))
          (is (= "xxx\n" (slurp (.getPath tmp)))))))

    (testing "local-file"
      (utils/with-temporary [tmp (utils/tmpfile)
                             target-tmp (utils/tmpfile)]
        ;; this is convoluted to get around the "t" sticky bit on temp dirs
        (let [user (local-test-user)]
          (.delete target-tmp)
          (io/copy "text" tmp)
          (let [compute (make-localhost-compute :group-name "local")
                local (group-spec "local" {})]
            (testing "local-file"
              (logging/debugf "local-file is %s" (.getPath tmp))
              (let [result (lift
                            local
                            :phase (plan-fn [session]
                                     (remote-file
                                      session
                                      (.getPath target-tmp)
                                      {:local-file (.getPath tmp)
                                       :mode "0666"}))
                            :compute compute
                            :user user)]
                (is (nil? (:exception result)))
                (is (nil? (phase-errors result)))
                (is (some
                     #(= (first (nodes compute)) %)
                     (map :node (:targets result)))))
              (is (.canRead target-tmp))
              (is (= "text" (slurp (.getPath target-tmp))))
              (is (slurp (str (upload-file-path
                               default-file-uploader
                               (.getPath target-tmp)
                               {:user user})
                              ".md5")))

              (testing "with md5 guard same content"
                (logging/info "remote-file test: local-file with md5 guard")
                (let [compute (make-localhost-compute :group-name "local")
                      result (lift
                              local
                              :phase (plan-fn [session]
                                       (remote-file
                                        session
                                        (.getPath target-tmp)
                                        {:local-file (.getPath tmp)
                                         :mode "0666"}))
                              :compute compute
                              :user user)]
                  (is (nil? (phase-errors result)))
                  (is (some
                       #(= (first (nodes compute)) %)
                       (map :node (:targets result))))))
              (testing "with md5 guard different content"
                (logging/info "remote-file test: local-file with md5 guard")
                (io/copy "text2" tmp)
                (let [compute (make-localhost-compute :group-name "local")
                      result (lift
                              local
                              :phase (plan-fn [session]
                                       (remote-file
                                        session
                                        (.getPath target-tmp)
                                        {:local-file (.getPath tmp)
                                         :mode "0666"}))
                              :compute compute
                              :user user)]
                  (is (nil? (phase-errors result)))
                  (is (nil? (:exception result)))
                  (is (some
                       #(= (first (nodes compute)) %)
                       (map :node (:targets result)))))))
            (testing "content"
              (let [result (lift
                            local
                            :phase (plan-fn [session]
                                     (remote-file
                                      session
                                      (.getPath target-tmp)
                                      {:content "$(hostname)"
                                       :mode "0666"
                                       :flag-on-changed "changed"}))
                            :compute compute
                            :user user)]
                (is (nil? (phase-errors result)))
                (is (nil? (:exception result)))
                (is (.canRead target-tmp))
                (is (= (:out (local/local-script "hostname"))
                       (slurp (.getPath target-tmp))))))
            (testing "content unchanged"
              (let [a (atom nil)]
                (lift
                 local
                 :compute compute
                 :phase (plan-fn [session]
                          (remote-file
                           session
                           (.getPath target-tmp)
                           {:content "$(hostname)"
                            :mode "0666" :flag-on-changed "changed"})
                          (is (not (target-node-state session :changed)))
                          (reset! a true))
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
                        :phase (plan-fn [session]
                                 (remote-file
                                  session
                                  (.getPath target-tmp)
                                  {:content "abc"
                                   :mode "0666" :flag-on-changed "changed"})
                                 (is (target-node-state session :changed))
                                 (reset! flag-verified true))
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
               :phase (plan-fn [session]
                        (remote-file
                         session
                         (.getPath target-tmp)
                         {:content "$text123" :literal true
                          :mode "0666"}))
               :user user)
              (is (.canRead target-tmp))
              (is (= "$text123\n" (slurp (.getPath target-tmp)))))
            (testing "remote-file"
              (io/copy "text" tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn [session]
                        (remote-file
                         session
                         (.getPath target-tmp)
                         {:remote-file (.getPath tmp)
                          :mode "0666"}))
               :user user)
              (is (.canRead target-tmp))
              (is (= "text" (slurp (.getPath target-tmp)))))
            (testing "url"
              (io/copy "urltext" tmp)
              (let [result (lift
                            local
                            :compute compute
                            :phase (plan-fn [session]
                                     (remote-file
                                      session
                                      (.getPath target-tmp)
                                      {:url (str "file://" (.getPath tmp))
                                       :mode "0666"}))
                            :user user)]
                (is result)
                (is (nil? (phase-errors result)))
                (is (.canRead target-tmp))
                (is (= "urltext" (slurp (.getPath target-tmp))))))
            (testing "url with md5"
              (io/copy "urlmd5text" tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn [session]
                        (remote-file
                         session
                         (.getPath target-tmp)
                         {:url (str "file://" (.getPath tmp))
                          :md5 (stevedore/script
                                @(~lib/md5sum ~(.getPath tmp)))
                          :mode "0666"}))
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
                      result (lift
                              local
                              :compute compute
                              :phase (plan-fn [session]
                                       ;; create md5 file to download
                                       (exec-script
                                        session
                                        ((lib/md5sum ~(.getPath tmp-copy))
                                         > ~md5path))
                                       (remote-file
                                        session
                                        (.getPath target-tmp)
                                        {:url (str "file://" (.getPath tmp))
                                         :md5-url (str "file://" md5path)
                                         :mode "0666"}))
                              :user user)]
                  (is (nil? (phase-errors result)))
                  (is (.canRead target-tmp))
                  (is (= "urlmd5urltext" (slurp (.getPath target-tmp)))))))
            (testing "delete action"
              (.createNewFile target-tmp)
              (lift
               local
               :compute compute
               :phase (plan-fn [session]
                        (remote-file
                         session (.getPath target-tmp) {:action :delete}))
               :user user)
              (is (not (.exists target-tmp))))))))

    ;; (testing "with :script-dir"
    ;;   (utils/with-temporary [tmp (utils/tmpfile)]
    ;;     (.delete tmp)
    ;;     (is (script-no-comment=
    ;;          (str "remote-file " (.getName tmp) "...\n"
    ;;               "MD5 sum is 6de9439834c9147569741d3c9c9fc010 "
    ;;               (.getName tmp) "\n"
    ;;               "#> remote-file " (.getName tmp) " : SUCCESS")
    ;;          (let [compute (make-localhost-compute :group-name "local")
    ;;                op (lift
    ;;                    (group-spec "local" {})
    ;;                    :phase (plan-fn [session]
    ;;                             (with-action-options session
    ;;                               {:script-dir (.getParent tmp)}
    ;;                               (remote-file
    ;;                                session (.getName tmp) :content "xxx")))
    ;;                    :compute compute
    ;;                    :user (local-test-user)
    ;;                    :async true)
    ;;                session result]
    ;;            (is (not (phase-errors result)))
    ;;            (is (nil? (phase-errors result)))
    ;;            (logging/infof "r-f-t content: session %s" session)
    ;;            (->> session :results (mapcat :result) first :out))))
    ;;     (is (= "xxx\n" (slurp (.getPath tmp))))))
    ))

;; (deftest rsync-file-upload-test
;;   (testing "local-file via rsync"
;;     (utils/with-temporary [tmp (utils/tmpfile)
;;                            target-tmp (utils/tmpfile)]
;;       ;; this is convoluted to get around the "t" sticky bit on temp dirs
;;       (let [user (local-test-user)]
;;         (.delete target-tmp)
;;         (io/copy "text" tmp)
;;         (let [compute (make-localhost-compute :group-name "local")
;;               local (group-spec "local" {})]
;;           (testing "local-file"
;;             (logging/debugf "local-file is %s" (.getPath tmp))
;;             (let [session (lift
;;                            local
;;                            :phase (plan-fn [session]
;;                                     (remote-file
;;                                      session
;;                                      (.getPath target-tmp)
;;                                      :local-file (.getPath tmp)
;;                                      :mode "0666"))
;;                            :environment {:action-options
;;                                          {:file-uploader (rsync-upload {})}}
;;                            :compute compute
;;                            :user user)]
;;               (is (nil? (phase-errors session)))
;;               (is (some
;;                    #(= (first (nodes compute)) %)
;;                    (map :node (:targets session)))))
;;             (is (.canRead target-tmp))
;;             (is (= "text" (slurp (.getPath target-tmp))))))))))


;; (deftest no-state-upload-test
;;   (testing "local-file via rsync"
;;     (utils/with-temporary [tmp (utils/tmpfile)
;;                            target-tmp (utils/tmpfile)]
;;       ;; this is convoluted to get around the "t" sticky bit on temp dirs
;;       (let [user (local-test-user)]
;;         (.delete target-tmp)
;;         (io/copy "text" tmp)
;;         (let [compute (make-localhost-compute :group-name "local")
;;               local (group-spec "local" {})]
;;           (testing "local-file"
;;             (logging/debugf "local-file is %s" (.getPath tmp))
;;             (let [result (lift
;;                            local
;;                            :phase (plan-fn [session]
;;                                     (remote-file
;;                                      session
;;                                      (.getPath target-tmp)
;;                                      :local-file (.getPath tmp)
;;                                      :mode "0666"))
;;                            :environment {:action-options
;;                                          {:file-backup (no-backup)
;;                                           :file-checksum (no-checksum)}}
;;                            :compute compute
;;                            :user user)]
;;               (is (nil? (phase-errors result)))
;;               (is (some
;;                    #(= (first (nodes compute)) %)
;;                    (map :node (:targets result)))))
;;             (is (.canRead target-tmp))
;;             (is (= "text" (slurp (.getPath target-tmp))))))))))

;; (deftest transfer-file-to-local-test
;;   (utils/with-temporary [remote-file (utils/tmpfile)
;;                          local-file (utils/tmpfile)]
;;     (let [user (local-test-user)
;;           local (group-spec "local"
;;                   {:phases {:configure (plan-fn [session]
;;                                         (transfer-file-to-local
;;                                          session
;;                                          remote-file local-file))}})
;;           compute (make-localhost-compute :group-name "local")]
;;       (io/copy "text" remote-file)
;;       (testing "with local ssh"
;;         (let [node (test-utils/make-localhost-node)]
;;           (testing "with-remote-file"
;;             (lift local :compute compute :user user)
;;             (is (= "text" (slurp local-file)))))))))

;; (defn check-content
;;   [path content path-atom]
;;   (is (= content (slurp path)))
;;   (reset! path-atom path))

;; (deftest with-remote-file-test
;;   (with-admin-user (local-test-user)
;;     (utils/with-temporary [remote-file (utils/tmpfile)]
;;       (let [user (local-test-user)
;;             local (group-spec "local" {})
;;             compute (make-localhost-compute :group-name "local")]
;;         (io/copy "text" remote-file)
;;         (testing "with local ssh"
;;           (let [node (test-utils/make-localhost-node)
;;                 path-atom (atom nil)]
;;             (testing "with-remote-file"
;;               (lift
;;                local
;;                :compute compute
;;                :phase (plan-fn [session]
;;                         (with-remote-file
;;                           session
;;                           check-content
;;                           (.getPath remote-file) "text" path-atom))
;;                :user user)
;;               (is @path-atom)
;;               (is (not= (.getPath remote-file) (.getPath @path-atom))))))
;;         (testing "with local shell"
;;           (let [node (test-utils/make-localhost-node)
;;                 path-atom (atom nil)]
;;             (testing "with-remote-file"
;;               (lift
;;                local
;;                :compute compute
;;                :phase (plan-fn [session]
;;                         (with-remote-file
;;                           session
;;                           check-content
;;                           (.getPath remote-file) "text" path-atom))
;;                :user user
;;                ;; :middleware [translate-action-plan]
;;                )
;;               (is @path-atom)
;;               (is (not= (.getPath remote-file) (.getPath @path-atom))))))))))

;; (deftest remote-file-content-test
;;   (with-admin-user (local-test-user)
;;     (utils/with-temporary [tmp-file (utils/tmpfile)
;;                            tmp-file-2 (utils/tmpfile)]
;;       (let [user (local-test-user)
;;             local (group-spec "local" {})
;;             compute (make-localhost-compute :group-name "local")]
;;         (testing "with local ssh"
;;           (let [node (test-utils/make-localhost-node)]
;;             (testing "remote-file-content with explicit node-value"
;;               (io/copy "text" tmp-file)
;;               (let [seen (atom nil)
;;                     result
;;                     (lift
;;                      local
;;                      :compute compute
;;                      :phase
;;                      (plan-fn [session]
;;                        (let [content (remote-file-content
;;                                       session (.getPath tmp-file))
;;                              is-text (= content "text")]
;;                          (when is-text
;;                            (let [new-content (string/replace content "x" "s")]
;;                              (reset! seen true)
;;                              (remote-file
;;                               session
;;                               (.getPath tmp-file-2)
;;                               :content new-content)))))
;;                      :user user
;;                      :async true)]
;;                 @result
;;                 (is (not (phase-errors @result)))
;;                 (is (nil? (phase-errors @result)))
;;                 (when (phase-errors @result)
;;                   (when-let [e (:exception @result)]
;;                     (print-stack-trace (root-cause e))))
;;                 (is @seen)
;;                 (is (= (slurp (.getPath tmp-file-2)) "test\n"))
;;                 (flush)))
;;             (testing "remote-file-content with deref"
;;               (io/copy "text" tmp-file)
;;               (let [seen (atom nil)
;;                     result
;;                     (lift
;;                      local
;;                      :compute compute
;;                      :phase
;;                      (plan-fn [session]
;;                        (let [content (remote-file-content
;;                                       session (.getPath tmp-file))]
;;                          (when (= content "text")
;;                            (let [new-content (string/replace content "x" "s")]
;;                              (reset! seen true)
;;                              (remote-file
;;                               session
;;                               (.getPath tmp-file-2)
;;                               :content new-content)))))
;;                      :user user)]
;;                 (is (not (phase-errors result)))
;;                 (is (nil? (phase-errors result)))
;;                 (if (phase-errors result)
;;                   (when-let [e (:exception result)]
;;                     (print-cause-trace e)))
;;                 (is @seen)
;;                 (is (= (slurp (.getPath tmp-file-2)) "test\n"))
;;                 (flush)))
;;             (testing "remote-file-content with deref and eval'd args"
;;               (io/copy "text" tmp-file)
;;               (.delete tmp-file-2)
;;               (let [seen (atom nil)
;;                     result
;;                     (lift
;;                      local
;;                      :compute compute
;;                      :phase
;;                      (plan-fn [session]
;;                        (let [content (remote-file-content
;;                                       session (.getPath tmp-file))]
;;                          (when (= content "text")
;;                            (reset! seen true)
;;                            (remote-file-action
;;                             session
;;                             (.getPath tmp-file-2)
;;                             {:content (string/replace content "x" "s")}))))
;;                      :user user
;;                      :async true)]
;;                 (is @result)
;;                 (is (nil? (phase-errors @result)))
;;                 (when (phase-errors @result)
;;                   (when-let [e (:exception @result)]
;;                     (print-cause-trace e)))
;;                 (is (not (phase-errors @result)))
;;                 (is @seen)
;;                 (is (= (slurp (.getPath tmp-file-2)) "test\n"))
;;                 (flush)))
;;             (testing "remote-file-content with delayed to non-action"
;;               (io/copy "text" tmp-file)
;;               (.delete tmp-file-2)
;;               (let [seen (atom nil)
;;                     result
;;                     (lift
;;                      local
;;                      :compute compute
;;                      :phase
;;                      (plan-fn [session]
;;                        (let [content (remote-file-content
;;                                       session (.getPath tmp-file))]
;;                          (when (= content "text")
;;                            (reset! seen true)
;;                            (remote-file
;;                             session
;;                             (.getPath tmp-file-2)
;;                             :content (string/replace content "x" "s")))))
;;                      :user user
;;                      :async true)]
;;                 (is @result)
;;                 (is (not (phase-errors @result)))
;;                 (is (nil? (phase-errors @result)))
;;                 (is @seen)
;;                 (is (= (slurp (.getPath tmp-file-2)) "test\n"))
;;                 (flush)))
;;             (testing "remote-file-content with non-action"
;;               (io/copy "text" tmp-file)
;;               (.delete tmp-file-2)
;;               (let [seen (atom nil)
;;                     result
;;                     (lift
;;                      local
;;                      :compute compute
;;                      :phase
;;                      (plan-fn [session]
;;                        (let [content (remote-file-content
;;                                       session (.getPath tmp-file))]
;;                          (when (= content "text")
;;                            (reset! seen true)
;;                            (remote-file
;;                             session
;;                             (.getPath tmp-file-2)
;;                             :content (string/replace content "x" "s")))))
;;                      :user user
;;                      :async true)]
;;                 (is @result)
;;                 (is (not (phase-errors @result)))
;;                 (is @seen)
;;                 (is (= (slurp (.getPath tmp-file-2)) "test\n"))
;;                 (flush)))))))))
