(ns pallet.crate.couchdb
  (:use
   [pallet.resource.package :only [package]]))

(defn couchdb
  "Installs couchdb."
  []
  (package "couchdb"))
