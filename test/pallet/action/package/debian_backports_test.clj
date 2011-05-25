(ns pallet.action.package.debian-backports-test
  (:use
   pallet.action.package.debian-backports
   clojure.test)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(deftest debian-backports-test
  (is
   (=
    (first
     (build-actions/build-actions
      {:server {:image {:os-family :debian}}}
      (package/package-source
       "debian-backports"
       :aptitude {:url "http://backports.debian.org/debian-backports"
                  :release (str
                            (stevedore/script (~lib/os-version-name))
                            "-backports")
                  :scopes ["main"]})))
    (first
     (build-actions/build-actions
      {:server {:image {:os-family :debian}}}
      (add-debian-backports))))))
