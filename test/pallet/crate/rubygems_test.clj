(ns pallet.crate.rubygems-test
  (:use [pallet.crate.rubygems] :reload-all)
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]))

(use-fixtures :each with-null-target)

(deftest gem-script-test
  (is (= "gem install  fred"
         (script (gem "install" "fred")))))

(deftest gem-test
  (is (= (stevedore/checked-script
          "Install gem fred"
          (gem "install" "fred"))
         (build-resources [] (gem "fred")))))

(deftest gem-source-test
  (is (= "if ! gem sources --list | grep http://rubygems.org; then gem sources --add http://rubygems.org;fi\n"
         (build-resources [] (gem-source "http://rubygems.org")))))

(deftest gemrc-test
  (is (= (remote-file/remote-file*
          "$(getent passwd fred | cut -d: -f6)/.gemrc"
          :content "\"gem\":\"--no-rdoc --no-ri\""
          :owner "fred")
         (build-resources [] (gemrc {:gem "--no-rdoc --no-ri"} "fred")))))
