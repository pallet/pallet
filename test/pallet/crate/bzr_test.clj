(ns pallet.crate.bzr-test
  (:use pallet.crate.bzr)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest bzr-test
  []
  (let [a {:tag :n :image {:os-family :ubuntu}}]
    (is (= (stevedore/checked-commands
            "Packages"
            (stevedore/script (package-manager-non-interactive))
            (package/package* {:node-type a} "bzr")
            (package/package* {:node-type a} "bzrtools"))
           (first
            (resource/build-resources
             [:node-type a]
             (bzr)))))))
