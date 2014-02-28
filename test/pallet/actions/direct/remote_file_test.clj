(ns pallet.actions.direct.remote-file-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions.direct.file :refer [adjust-file]]
   [pallet.actions.direct.remote-file
    :refer [default-backup default-checksum default-file-uploader remote-file*]]
   [pallet.actions.impl :refer [checked-commands]]
   [pallet.core.file-upload
    :refer [upload-file upload-file-path user-file-path]]
   [pallet.script :as script]
   [pallet.script.lib :as lib :refer [user-home]]
   [pallet.ssh.node-state :refer [verify-checksum]]
   [pallet.ssh.node-state
    :refer [new-file-content record-checksum verify-checksum]]
   [pallet.ssh.node-state.no-state :refer [no-backup no-checksum]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language)

(def action-state {:options {:user {:username "fred" :password "x"}}})
(def action-options (:options action-state))

(deftest remote-file*-test
  (let [new-path (user-file-path default-file-uploader "path" action-options)]
    (testing "url"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (verify-checksum default-checksum action-options "path")
            (stevedore/chained-script
             (lib/mkdir @(lib/dirname ~new-path) :path true)
             (lib/download-file "http://a.com/b" ~new-path)
             (var contentdiff "")
             (if (&& (file-exists? "path") (file-exists? ~new-path))
               (do
                 (lib/diff "path" ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
               ~(stevedore/chain-commands
                 (stevedore/script
                  (lib/cp ~new-path "path" :force ~true))
                 (new-file-content default-backup action-options "path" {})
                 (record-checksum default-checksum action-options "path")))))
           (second
            (remote-file*
             action-state
             "path" {:url "http://a.com/b" :install-new-files true})))))

    (testing "url with proxy"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (verify-checksum default-checksum action-options "path")
            (stevedore/chained-script
             (lib/mkdir @(lib/dirname ~new-path) :path true)
             (lib/download-file
              "http://a.com/b" ~new-path :proxy "http://proxy/")
             (var contentdiff "")
             (if (&& (file-exists? "path") (file-exists? ~new-path))
               (do
                 (lib/diff "path" ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
               ~(stevedore/chain-commands
                 (stevedore/script
                  (lib/cp ~new-path "path" :force ~true))
                 (new-file-content default-backup action-options "path" {})
                 (record-checksum default-checksum action-options "path")))))
           (second
            (remote-file* action-state "path" {:url "http://a.com/b"
                                               :proxy "http://proxy/"})))))

    (testing "content with no-versioning"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (verify-checksum default-checksum action-options "path")
            (stevedore/chained-script
             (lib/mkdir @(lib/dirname ~new-path) :path true)
             (lib/heredoc ~new-path "xxx" {})
             (var contentdiff "")
             (if (&& (file-exists? "path") (file-exists? ~new-path))
               (do
                 (lib/diff "path" ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
               ~(stevedore/chain-commands
                 (stevedore/script
                  (lib/cp ~new-path "path" :force ~true))
                 (new-file-content
                  default-backup action-options "path" {:no-versioning true})
                 (record-checksum default-checksum action-options "path")))))
           (second
            (remote-file* action-state "path" {:content "xxx"
                                               :no-versioning true
                                               :install-new-files true})))))

    (testing "content with owner, group and mode"
      (is (script-no-comment=
           (stevedore/checked-commands
            "remote-file path"
            (verify-checksum default-checksum action-options "path")
            (stevedore/chained-script
             (lib/mkdir @(lib/dirname ~new-path) :path true)
             (lib/heredoc ~new-path "xxx" {})
             (var contentdiff "")
             (if (&& (file-exists? "path") (file-exists? ~new-path))
               (do
                 (lib/diff "path" ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
               ~(stevedore/chain-commands
                 (stevedore/chained-script
                  (lib/cp ~new-path "path" :force ~true))
                 (adjust-file "path" {:owner "o" :group "g" :mode "m"})
                 (new-file-content default-backup action-options "path" {})
                 (record-checksum default-checksum action-options "path")))))
           (second
            (remote-file*
             action-state "path" {:content "xxx"
                                  :owner "o" :group "g" :mode "m"}))))))

  (testing "local-file"
    (is (script-no-comment=
         (let [new-path (upload-file-path
                         default-file-uploader "path" action-options)]
           (stevedore/checked-commands
            "remote-file path"
            (verify-checksum default-checksum action-options "path")
            (stevedore/chained-script
             (lib/mkdir @(lib/dirname ~new-path) :path true)
             (if-not (file-exists? ~new-path)
               (lib/exit 2))
             (var contentdiff "")
             (if (&& (file-exists? "path") (file-exists? ~new-path))
               (do
                 (lib/diff "path" ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
               ~(stevedore/chain-commands
                 (stevedore/chained-script
                  (lib/cp ~new-path "path" :force ~true))
                 (adjust-file "path" {:owner "o" :group "g" :mode "m"})
                 (new-file-content default-backup action-options "path" {})
                 (record-checksum default-checksum action-options "path"))))))
         (second
          (remote-file*
           action-state "path" {:local-file "xxx"
                                :owner "o" :group "g" :mode "m"})))))

  (testing "delete"
    (is
     (stevedore/checked-script
      "delete remote-file path"
      ("rm" "--force" "path"))
     (remote-file* {} "path" {:action :delete :force true}))))
