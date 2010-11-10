(ns pallet.crate.chef
 "Installation of chef"
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.resource.directory :only [directory]]
   [pallet.stevedore :only [script]]
   [pallet.utils :only [*admin-user*]]
   [pallet.crate.rubygems :only [gem gem-source rubygems]])
  (:require
   [pallet.resource.exec-script :as exec-script]
   [pallet.parameter :as parameter]))

(defn chef
  "Install chef"
  ([request] (chef request "/var/lib/chef"))
  ([request cookbook-dir]
     (->
      request
      (package "rsync")
      (rubygems)
      (gem-source "http://rubygems.org/")
      (gem "chef")
      (directory cookbook-dir :owner (:username *admin-user*))
      (parameter/assoc-for-target [:chef :cookbook-dir] cookbook-dir))))

(defn solo
  "Run chef solo"
  [request command]
  (let [cookbook-dir (parameter/get-for-target request [:chef :cookbook-dir])]
    (->
     request
     (exec-script/exec-checked-script
      "Chef solo"
      (chef-solo
       -c ~(str cookbook-dir "/config/solo.rb")
       -j ~(str cookbook-dir "/config/" command ".json"))))))
