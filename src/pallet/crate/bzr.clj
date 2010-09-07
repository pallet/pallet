(ns pallet.crate.bzr
  (:require
   [pallet.resource.package :as package]))

(defn bzr
  "Install bzr"
  [request]
  (-> request
      (package/package "bzr")
      (package/package "bzrtools")))
