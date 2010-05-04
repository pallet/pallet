(ns pallet.crate.git-test
  (:use [pallet.crate.git] :reload-all)
  (:require
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest git-test
  []
  (is (= (target/with-target nil {:tag :n :image [:ubuntu]}
           (stevedore/checked-commands
            "Packages"
            (package/package* "git-core")
            (package/package* "git-email")))
         (test-resource-build
          [nil {:tag :n :image [:ubuntu]}]
          (git))))
  (is (= (target/with-target nil {:tag :n :image [:centos]}
           (stevedore/checked-commands
            "Packages"
            (package/package* "git")
            (package/package* "git-email")))
         (test-resource-build
          [nil {:tag :n :image [:centos]}]
          (git)))))
