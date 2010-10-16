(ns pallet.crate.rubygems-test
  (:use pallet.crate.rubygems)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore])
  (:use
   clojure.test
   pallet.test-utils
   [pallet.resource.package :only [package package-manager]]))

(use-fixtures :once with-ubuntu-script-template)

(deftest gem-script-test
  (is (= "gem install  fred"
         (stevedore/script (gem install fred)))))

(deftest gem-test
  (is (= (first
          (build-resources
           []
           (exec-script/exec-checked-script
             "Install gem fred"
             (gem install fred))))
         (first (build-resources [] (gem "fred"))))))

(deftest gem-source-test
  (is (= (first
          (build-resources
           []
           (exec-script/exec-script
            "if ! gem sources --list | grep http://rubygems.org; then gem sources --add http://rubygems.org;fi\n")))
         (first (build-resources [] (gem-source "http://rubygems.org"))))))

(deftest gemrc-test
  (is (= (first
          (build-resources
           []
           (remote-file/remote-file
            "$(getent passwd fred | cut -d: -f6)/.gemrc"
            :content "\"gem\":\"--no-rdoc --no-ri\""
            :owner "fred")))
         (first
          (build-resources [] (gemrc {:gem "--no-rdoc --no-ri"} "fred"))))))

(deftest invoke-test
  (is (build-resources
       []
       (rubygems)
       (rubygems-update)
       (gem "name")
       (gem "name" :action :delete)
       (gem-source "http://rubygems.org")
       (gemrc {} "user")
       (require-rubygems))))
