(ns pallet.crate.git-test
  (:use pallet.crate.git)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest git-test
  []
  (let [a {:tag :n :image [:ubuntu]}]
    (is (= (stevedore/checked-commands
            "Packages"
            (stevedore/script (package-manager-non-interactive))
            (package/package* {:node-type a} "git-core")
            (package/package* {:node-type a} "git-email"))
           (first
            (resource/build-resources
             [:node-type a]
             (git))))))
  (let [a {:tag :n :image [:centos]}]
    (is (= (script/with-template (:image a)
             (stevedore/checked-commands
              "Packages"
              (stevedore/script (package-manager-non-interactive))
              (package/package* {:node-type a} "git")
              (package/package* {:node-type a} "git-email")))
           (first
            (resource/build-resources
             [:node-type a]
             (git)))))))
