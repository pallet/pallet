(ns pallet.crate.rubygems-test
  (:use [pallet.crate.rubygems] :reload-all)
  (:require [pallet.template :only [apply-templates]])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]
        [clojure.contrib.java-utils :only [file]]))

(deftest gem-script-test
  (is (= "gem install  fred"
         (script (gem "install" "fred")))))

(deftest gem-test
  (is (= "gem install  fred\n"
         (build-resources [] (gem "fred")))))

(deftest gem-source-test
  (is (= "if ! gem sources --list | grep http://rubygems.org; then gem sources --add http://rubygems.org;fi\n"
         (build-resources [] (gem-source "http://rubygems.org")))))

(deftest gemrc-test
  (is (= "cat > $(getent passwd quote user | cut -d: -f6)/.gemrc <<EOF\n\"gem\":\"--no-rdoc --no-ri\"\nEOF\nchown  fred $(getent passwd quote user | cut -d: -f6)/.gemrc\n"
         (build-resources [] (gemrc {:gem "--no-rdoc --no-ri"} "fred")))))
