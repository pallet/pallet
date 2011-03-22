(ns pallet.resource.remote-directory-test
  (:use pallet.resource.remote-directory)
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.utils :as utils]))

(use-fixtures :once with-ubuntu-script-template)

(def directory* (action/action-fn directory/directory))
(def remote-file* (action/action-fn remote-file/remote-file-resource))

(deftest remote-directory-test
  (is (= (stevedore/checked-commands
          "remote-directory"
          (directory* {} "/path" :owner "fred")
          (remote-file* {}
           "${TMPDIR-/tmp}/file.tgz" :url "http://site.com/a/file.tgz" :md5 nil)
          (stevedore/script
           ("cd" "/path")
           ("tar" xz "--strip-components=1" -f "${TMPDIR-/tmp}/file.tgz")))
         (first (build-actions/build-actions
                 {}
                 (remote-directory
                  "/path"
                  :url "http://site.com/a/file.tgz"
                  :unpack :tar
                  :owner "fred")))))
  (is (= (stevedore/checked-commands
          "remote-directory"
          (directory* {} "/path" :owner "fred")
          (remote-file*
           {} "${TMPDIR-/tmp}/file.tgz"
           :url "http://site.com/a/file.tgz" :md5 nil)
          (stevedore/script
           ("cd" "/path")
           ("tar" xz "--strip-components=1" -f "${TMPDIR-/tmp}/file.tgz"))
          (directory* {} "/path" :owner "fred" :recursive true))
         (first (build-actions/build-actions
                 {}
                 (remote-directory
                  "/path"
                  :url "http://site.com/a/file.tgz"
                  :unpack :tar
                  :owner "fred"
                  :recursive true))))))
