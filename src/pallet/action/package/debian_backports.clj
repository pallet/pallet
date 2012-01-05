(ns pallet.action.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.action.package :as package]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.phase :only [defcrate]]))

(defcrate add-debian-backports
  "Add debian backport package repository"
  (package/package-source
   "debian-backports"
   :aptitude {:url "http://backports.debian.org/debian-backports"
              :release (str
                        (stevedore/script (~lib/os-version-name)) "-backports")
              :scopes ["main"]}))
