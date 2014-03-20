(ns pallet.actions.direct.remote-directory-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [action-fn]]
   [pallet.actions :refer [directory remote-directory]]
   [pallet.actions-impl :refer [remote-file-action]]
   [pallet.actions.direct.remote-file :refer [default-content-files]]
   [pallet.build-actions :as build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.session :refer [session with-session]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.script.lib :as lib]
   [pallet.ssh.content-files :refer [content-path]]
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
  (is (=
       (binding [pallet.action-plan/*defining-context* nil]
         (with-session {:environment {:user *admin-user*}
                        :user *admin-user*}
           (let [path (content-path
                       default-content-files (session) {} "/path/a/file.tgz")
                 md5-path (str path ".md5")]
             (stevedore/do-script
              (stevedore/checked-commands
               "remote-directory"
               (-> (directory* {} "/path" :owner "fred" :recursive false)
                   first second)
               (-> (directory* {} (fragment @(lib/dirname ~path))) first second)
               (-> (remote-file*
                    (session) path {:url "http://site.com/a/file.tgz" :md5 nil})
                   first second)
               (stevedore/script
                (when (or (not (file-exists? ~md5-path))
                          (or (not (file-exists? "/path/.pallet.directory.md5"))
                              (not ("diff" ~md5-path
                                    "/path/.pallet.directory.md5"))))
                  ~(stevedore/checked-script
                    (str "Untar " path)
                    (var rdf @("readlink" -f ~path))
                    ("cd" "/path")
                    ("tar" xz "--strip-components=1" -f "${rdf}")
                    ("cd" -))
                  (when (file-exists? ~md5-path)
                    ("cp" ~md5-path "/path/.pallet.directory.md5"))))
               (-> (directory* {} "/path" :owner "fred" :recursive true)
                   first second))))))
       (first
        (build-actions {:environment {:user *admin-user*}}
          (remote-directory
           "/path"
           :url "http://site.com/a/file.tgz"
           :unpack :tar
           :owner "fred")))))
  (is (=
       (with-session {:environment {:user *admin-user*}
                      :user *admin-user*}
         (binding [pallet.action-plan/*defining-context* nil]
           (let [path (content-path
                       default-content-files (session) {} "/path/a/file.tgz")
                 md5-path (str path ".md5")]
             (stevedore/do-script
              (stevedore/checked-commands
               "remote-directory"
               (-> (directory* {} "/path" :owner "fred" :recursive false)
                   first second)
               (-> (directory* {} (fragment @(lib/dirname ~path))) first second)
               (-> (remote-file*
                    (session) path {:url "http://site.com/a/file.tgz" :md5 nil})
                   first second)
               (stevedore/script
                (when (or (not (file-exists? ~md5-path))
                          (or (not (file-exists? "/path/.pallet.directory.md5"))
                              (not ("diff" ~md5-path
                                    "/path/.pallet.directory.md5"))))
                  ~(stevedore/checked-script
                    (str "Untar " path)
                    (var rdf @("readlink" -f ~path))
                    ("cd" "/path")
                    ("tar" xz "--strip-components=1" -f "${rdf}")
                    ("cd" -))
                  (when (file-exists? ~md5-path)
                    ("cp" ~md5-path "/path/.pallet.directory.md5")))))))))
       (first (build-actions {}
                (remote-directory
                 "/path"
                 :url "http://site.com/a/file.tgz"
                 :unpack :tar
                 :owner "fred"
                 :recursive false)))))
  (is (=
       (with-session {:environment {:user *admin-user*}
                      :user *admin-user*}
         (binding [pallet.action-plan/*defining-context* nil]
           (let [path (content-path
                       default-content-files (session) {} "/path/a/file.tgz")
                 md5-path (str path ".md5")]
             (stevedore/do-script
              (stevedore/checked-commands
               "remote-directory"
               (-> (directory* {} "/path" :owner "fred" :recursive false)
                   first second)
               (-> (directory* {} (fragment @(lib/dirname ~path))) first second)
               (-> (remote-file*
                    (session) path {:url "http://site.com/a/file.tgz" :md5 nil})
                   first second)
               (stevedore/script
                (when (or (not (file-exists? ~md5-path))
                          (or (not (file-exists? "/path/.pallet.directory.md5"))
                              (not ("diff" ~md5-path
                                    "/path/.pallet.directory.md5"))))
                  ~(stevedore/checked-script
                    (str "Untar " path)
                    (var rdf @("readlink" -f ~path))
                    ("cd" "/path")
                    ("tar" xz "--strip-components=0" -f "${rdf}"
                     "dir/file file2")
                    ("cd" -))
                  (when (file-exists? ~md5-path)
                    ("cp" ~md5-path "/path/.pallet.directory.md5")))))))))
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
