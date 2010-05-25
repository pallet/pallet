(ns pallet.crate.chef
 "Installation of chef"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.resource.directory :only [directory]]
   [pallet.stevedore :only [script]]
   [pallet.utils :only [*admin-user*]]
   [pallet.crate.rubygems :only [gem gem-source rubygems]]
   [pallet.target :only [packager]]))

(defn chef
  "Install chef"
  ([] (chef "/var/lib/chef"))
  ([cookbook-dir]
     (package "rsync")
     (rubygems)
     (gem-source "http://rubygems.org/")
     (gem "chef")
     (directory cookbook-dir :owner (:username *admin-user*))))

