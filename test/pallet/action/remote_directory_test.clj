(ns pallet.action.remote-directory-test
  (:use pallet.action.remote-directory)
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

(use-fixtures :once with-ubuntu-script-template)

(def directory* (action/action-fn directory/directory))
(def remote-file* (action/action-fn remote-file/remote-file-action))

(deftest remote-directory-test
  (is (= (stevedore/checked-commands
          "remote-directory"
          (directory* {} "/path" :owner "fred" :recursive false)
          (remote-file* {}
           "${TMPDIR-/tmp}/file.tgz" :url "http://site.com/a/file.tgz" :md5 nil)
          (stevedore/checked-script
           "Untar ${TMPDIR-/tmp}/file.tgz"
           (var rdf @(readlink -f "${TMPDIR-/tmp}/file.tgz"))
           (cd "/path")
           (tar xz "--strip-components=1" -f "${rdf}")
           (cd -))
          (directory* {} "/path" :owner "fred" :recursive true))
         (first (build-actions/build-actions
                 {}
                 (remote-directory
                  "/path"
                  :url "http://site.com/a/file.tgz"
                  :unpack :tar
                  :owner "fred")))))
  (is (= (stevedore/checked-commands
          "remote-directory"
          (directory* {} "/path" :owner "fred" :recursive false)
          (remote-file*
           {} "${TMPDIR-/tmp}/file.tgz"
           :url "http://site.com/a/file.tgz" :md5 nil)
          (stevedore/checked-script
           "Untar ${TMPDIR-/tmp}/file.tgz"
           (var rdf @(readlink -f "${TMPDIR-/tmp}/file.tgz"))
           (cd "/path")
           (tar xz "--strip-components=1" -f "${rdf}")
           (cd -)))
         (first (build-actions/build-actions
                 {}
                 (remote-directory
                  "/path"
                  :url "http://site.com/a/file.tgz"
                  :unpack :tar
                  :owner "fred"
                  :recursive false))))))
