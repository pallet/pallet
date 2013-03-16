(ns pallet.actions.direct.remote-directory-test
  (:use
   clojure.test
   pallet.test-utils
   [pallet.action :only [action-fn]]
   [pallet.actions :only [directory remote-directory remote-file]]
   [pallet.actions-impl :only [remote-file-action]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.core.session :only [with-session]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.utils :refer [with-temporary tmpfile]])
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 (logging-threshold-fixture))

(def directory* (action-fn directory :direct))
(def remote-file* (action-fn remote-file-action :direct))

(deftest remote-directory-test
  (assert pallet.core.session/*session*)
  (is (script-no-comment=
       (binding [pallet.action-plan/*defining-context* nil]
         (with-session {:user *admin-user*}
           (stevedore/checked-commands
            "remote-directory"
            (-> (directory* {} "/path" :owner "fred" :recursive false)
                first second)
            (-> (remote-file*
                 {}
                 "${TMPDIR-/tmp}/file.tgz"
                 {:url "http://site.com/a/file.tgz" :md5 nil})
                first second)
            (stevedore/script
             (when (or (not (file-exists? "${TMPDIR-/tmp}/file.tgz.md5"))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" "${TMPDIR-/tmp}/file.tgz.md5"
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 "Untar ${TMPDIR-/tmp}/file.tgz"
                 (var rdf @("readlink" -f "${TMPDIR-/tmp}/file.tgz"))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? "${TMPDIR-/tmp}/file.tgz.md5")
                 ("cp" "${TMPDIR-/tmp}/file.tgz.md5"
                  "/path/.pallet.directory.md5"))))
            (-> (directory* {} "/path" :owner "fred" :recursive true)
                first second))))
       (first (build-actions/build-actions
                  {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :owner "fred")))))
  (is (script-no-comment=
       (with-session {:user *admin-user*}
         (binding [pallet.action-plan/*defining-context* nil]
           (stevedore/checked-commands
            "remote-directory"
            (-> (directory* {} "/path" :owner "fred" :recursive false)
                first second)
            (-> (remote-file*
                 {} "${TMPDIR-/tmp}/file.tgz"
                 {:url "http://site.com/a/file.tgz" :md5 nil})
                first second)
            (stevedore/script
             (when (or (not (file-exists? "${TMPDIR-/tmp}/file.tgz.md5"))
                       (or (not (file-exists? "/path/.pallet.directory.md5"))
                           (not ("diff" "${TMPDIR-/tmp}/file.tgz.md5"
                                 "/path/.pallet.directory.md5"))))
               ~(stevedore/checked-script
                 "Untar ${TMPDIR-/tmp}/file.tgz"
                 (var rdf @("readlink" -f "${TMPDIR-/tmp}/file.tgz"))
                 ("cd" "/path")
                 ("tar" xz "--strip-components=1" -f "${rdf}")
                 ("cd" -))
               (when (file-exists? "${TMPDIR-/tmp}/file.tgz.md5")
                 ("cp" "${TMPDIR-/tmp}/file.tgz.md5"
                  "/path/.pallet.directory.md5")))))))
       (first (build-actions/build-actions
                  {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
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
