(ns pallet.action.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.action.package :as package]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]))

(defn add-debian-backports
  "Add debian backport package repository"
  [session]
  (package/package-source
   session
   "debian-backports"
   :aptitude {:url "http://backports.debian.org/debian-backports"
              :release (str
                        (stevedore/script (~lib/os-version-name)) "-backports")
              :scopes ["main"]}))
