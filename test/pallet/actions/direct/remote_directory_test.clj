(ns pallet.actions.direct.remote-directory-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [action-fn]]
   [pallet.actions :refer [directory exec-checked-script remote-directory]]
   [pallet.actions.decl :refer [remote-file-action]]
   [pallet.actions.direct.remote-file :refer [create-path-with-template]]
   [pallet.build-actions :as build-actions :refer [build-actions build-script]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.session :refer [with-session]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils
    :refer [with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]
   [pallet.utils :refer [tmpfile with-temporary]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 with-no-source-line-comments
 (logging-threshold-fixture))

(def directory* (action-fn directory :direct))
(def remote-file* (action-fn remote-file-action :direct))

(deftest remote-directory-test
  (assert pallet.core.session/*session*)
  (is (script-no-comment=
       (build-script {}
         (directory "/path" :owner "fred" :recursive false)
         (exec-checked-script
          "remote-directory"
          ~(-> (remote-file*
                (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                {:url "http://site.com/a/file.tgz" :md5 nil})
                second)
          (when (or (not (file-exists?
                          (lib/file (lib/tmp-dir) "file.tgz.md5")))
                    (or (not (file-exists? "/path/.pallet.directory.md5"))
                        (not ("diff" (lib/file (lib/tmp-dir) "file.tgz.md5")
                              "/path/.pallet.directory.md5"))))
            ~(stevedore/checked-script
              (str "Untar " (fragment (lib/file (lib/tmp-dir) "file.tgz")))
              (var rdf @("readlink" -f (lib/file (lib/tmp-dir) "file.tgz")))
              ("cd" "/path")
              ("tar" xz "--strip-components=1" -f "${rdf}")
              ("cd" -))
            (when (file-exists? (lib/file (lib/tmp-dir) "file.tgz.md5"))
              ("cp" (lib/file (lib/tmp-dir) "file.tgz.md5")
               "/path/.pallet.directory.md5"))))
         (directory "/path" :owner "fred" :recursive true))
       (build-script {}
         (remote-directory
          "/path"
          :url "http://site.com/a/file.tgz"
          :unpack :tar
          :owner "fred"))))
  (is (script-no-comment=
       (first
        (build-actions {}
          (directory "/path" :owner "fred" :recursive false)
          (exec-checked-script
           "remote-directory"

           ~(-> (remote-file*
                 (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                 {:url "http://site.com/a/file.tgz" :md5 nil})
                second)
           ~(stevedore/script
             (when (or (not (file-exists?
                             (lib/file (lib/tmp-dir) "file.tgz.md5")))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" (lib/file (lib/tmp-dir) "file.tgz.md5")
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " (fragment (lib/file (lib/tmp-dir) "file.tgz")))
                 (var rdf @("readlink" -f (lib/file (lib/tmp-dir) "file.tgz")))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? (lib/file (lib/tmp-dir) "file.tgz.md5"))
                 ("cp" (lib/file (lib/tmp-dir) "file.tgz.md5")
                  "/path/.pallet.directory.md5")))))))
       (first (build-actions/build-actions
                  {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :owner "fred"
                 :recursive false)))))
  (is (script-no-comment=
       (first
        (build-actions {}
          (directory "/path" :owner "fred" :recursive false)
          (exec-checked-script
           "remote-directory"
           ~(-> (remote-file*
                 (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                 {:url "http://site.com/a/file.tgz" :md5 nil})
                second)
           ~(stevedore/script
             (when (or (not (file-exists?
                             (lib/file (lib/tmp-dir) "file.tgz.md5")))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" (lib/file (lib/tmp-dir) "file.tgz.md5")
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 (str "Untar " (fragment (lib/file (lib/tmp-dir) "file.tgz")))
                 (var rdf @("readlink" -f (lib/file (lib/tmp-dir) "file.tgz")))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=0" -f "${rdf}" "dir/file file2")
                 ("cd" -))
               (when (file-exists? (lib/file (lib/tmp-dir) "file.tgz.md5"))
                 ("cp" (lib/file (lib/tmp-dir) "file.tgz.md5")
                  "/path/.pallet.directory.md5")))))))
       (first (build-actions/build-actions
                  {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :strip-components 0
                 :extract-files ["dir/file" "file2"]
                 :owner "fred"
                 :recursive false)))))
  (with-temporary [tmp (tmpfile)]
    (is (first (build-actions/build-actions
                   {}
                 (remote-directory
                  "/path"
                  :local-file (.getPath tmp)
                  :unpack :tar
                  :owner "fred"
                  :recursive false))))))
