(ns pallet.actions.direct.remote-directory-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions.direct.directory :refer [directory*]]
   [pallet.actions.direct.remote-file
    :refer [default-file-uploader remote-file*]]
   [pallet.actions.direct.remote-directory :refer [remote-directory*]]
   [pallet.core.file-upload
    :refer [upload-file upload-file-path user-file-path]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils
    :refer [with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 with-no-source-line-comments)

(def action-state {:options {:user {:username "fred" :password "x"}}})
(def action-options (:options action-state))

(defn new-filename [path]
  (user-file-path default-file-uploader path action-options))

(deftest remote-directory-test
  (let [new-path (new-filename "/path")
        md5-path (str new-path ".md5")]
    (testing "url"
      (is (=
           (stevedore/chain-commands
            (stevedore/checked-script
             "remote-directory"
             ~(-> (remote-file*
                   action-state new-path
                   {:url "http://site.com/a/file.tgz" :md5 nil})
                  second)
             (when (or (not (file-exists? ~md5-path))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" ~md5-path
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " new-path)
                 (var rdf @("readlink" -f ~new-path))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? ~md5-path)
                 ("cp" ~md5-path "/path/.pallet.directory.md5")))))
           (remote-directory*
            action-state
            "/path"
            {:url "http://site.com/a/file.tgz"
             :unpack :tar
             :owner "fred"}))))

    (testing "url with recursive"
      (is (=
           (stevedore/chain-commands
            (stevedore/checked-script
             "remote-directory"
             ~(-> (remote-file*
                   action-state new-path
                   {:url "http://site.com/a/file.tgz" :md5 nil})
                  second)
             (when (or (not (file-exists? ~md5-path))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" ~md5-path
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " new-path)
                 (var rdf @("readlink" -f ~new-path))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? ~md5-path)
                 ("cp" ~md5-path "/path/.pallet.directory.md5")))))
           (remote-directory*
            action-state
            "/path"
            {:url "http://site.com/a/file.tgz"
             :unpack :tar
             :owner "fred"
             :recursive false}))))

    (testing "extract-files and strip-components"
      (is (=
           (stevedore/chain-commands
            (stevedore/checked-script
             "remote-directory"
             ~(-> (remote-file*
                   action-state new-path
                   {:url "http://site.com/a/file.tgz" :md5 nil})
                  second)
             (when (or (not (file-exists? ~md5-path))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" ~md5-path
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " new-path)
                 (var rdf @("readlink" -f ~new-path))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=0" -f "${rdf}" "dir/file file2")
                 ("cd" -))
               (when (file-exists? ~md5-path)
                 ("cp" ~md5-path "/path/.pallet.directory.md5")))))
           (remote-directory*
            action-state
            "/path"
            {:url "http://site.com/a/file.tgz"
             :unpack :tar
             :strip-components 0
             :extract-files ["dir/file" "file2"]
             :owner "fred"
             :recursive false})))))

  (let [new-path (upload-file-path default-file-uploader "/path" action-options)
        md5-path (str new-path ".md5")]
    (testing "local-file"
      (is (=
           (stevedore/chain-commands
            (stevedore/checked-script
             "remote-directory"
             (when (or (not (file-exists? ~md5-path))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" ~md5-path
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " new-path)
                 (var rdf @("readlink" -f ~new-path))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? ~md5-path)
                 ("cp" ~md5-path "/path/.pallet.directory.md5")))))
           (remote-directory*
            action-state
            "/path"
            {:local-file "/local-file"
             :unpack :tar
             :owner "fred"
             :recursive false}))))))
