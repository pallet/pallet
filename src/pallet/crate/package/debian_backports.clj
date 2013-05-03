(ns pallet.crate.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.actions :refer [package-source]]
   [pallet.crate :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(defplan add-debian-backports
  "Add debian backport package repository"
  []
  (package-source
   "debian-backports"
   :aptitude {:url "http://backports.debian.org/debian-backports"
              :release (str
                        (stevedore/script (~lib/os-version-name)) "-backports")
              :scopes ["main"]}))
