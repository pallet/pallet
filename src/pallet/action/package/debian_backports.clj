(ns pallet.action.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.action.package :only [package-source]]
   [pallet.utils :only [apply-map]]))

(defn debian-backports-repository
  "debian backport package repository"
  []
  {:name "debian-backports"
   :aptitude {:url "http://backports.debian.org/debian-backports"
              :release (str
                        (stevedore/script (~lib/os-version-name)) "-backports")
              :scopes ["main"]}
   :apt {:url "http://backports.debian.org/debian-backports"
         :release (str (stevedore/script (~lib/os-version-name)) "-backports")
         :scopes ["main"]}})

(defn add-debian-backports
  "Add debian backport package repository"
  [session]
  (let [source (debian-backports-repository)]
    (apply-map package-source session (:name source) source)))
