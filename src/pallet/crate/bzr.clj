(ns pallet.crate.bzr
  (:require
   [pallet.resource.package :as package]))

(defn bzr
  "Install bzr"
  []
  (package/package "bzr")
  (package/package "bzrtools"))
