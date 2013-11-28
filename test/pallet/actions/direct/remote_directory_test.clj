(ns pallet.actions.direct.remote-directory-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [action-fn]]
   [pallet.actions :refer [directory remote-directory]]
   [pallet.actions-impl :refer [remote-file-action]]
   [pallet.build-actions :as build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.session :refer [session with-session]]
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
       (binding [pallet.action-plan/*defining-context* nil]
         (with-session {:environment {:user *admin-user*}}
           (stevedore/do-script
            (stevedore/checked-commands
             "remote-directory"
             (-> (directory* {} "/path" :owner "fred" :recursive false)
                 first second)
             (-> (remote-file*
                  (session)
                  (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                  {:url "http://site.com/a/file.tgz" :md5 nil})
                 first second)
             (stevedore/script
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
             (-> (directory* {} "/path" :owner "fred" :recursive true)
                 first second)))))
       (first (build-actions {:environment {:user *admin-user*}}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :owner "fred")))))
  (is (script-no-comment=
       (with-session {:environment {:user *admin-user*}}
         (binding [pallet.action-plan/*defining-context* nil]
           (stevedore/do-script
            (stevedore/checked-commands
             "remote-directory"
             (-> (directory* {} "/path" :owner "fred" :recursive false)
                 first second)
             (-> (remote-file*
                  (session) (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                  {:url "http://site.com/a/file.tgz" :md5 nil})
                 first second)
             (stevedore/script
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
                   "/path/.pallet.directory.md5"))))))))
       (first (build-actions {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :owner "fred"
                 :recursive false)))))
  (is (script-no-comment=
       (with-session {:environment {:user *admin-user*}}
         (binding [pallet.action-plan/*defining-context* nil]
           (stevedore/do-script
            (stevedore/checked-commands
             "remote-directory"
             (-> (directory* {} "/path" :owner "fred" :recursive false)
                 first second)
             (-> (remote-file*
                  (session) (fragment (lib/file (lib/tmp-dir) "file.tgz"))
                  {:url "http://site.com/a/file.tgz" :md5 nil})
                 first second)
             (stevedore/script
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
                   "/path/.pallet.directory.md5"))))))))
       (first (build-actions {}
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
