(ns pallet.crate.chef
 "Installation of chef"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.crate.rubygems :only [gem gem-source rubygems]]
   [pallet.target :only [packager]]))

(defn chef
 "Install chef"
 []
 (rubygems)
 (gem-source "http://rubygems.org/")
 (gem "chef"))

