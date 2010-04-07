(ns pallet.crate.couchdb
  (:use
   [pallet.resource.package :only [packages]]))

(defn couchdb
  "Installs couchdb."
  []
  (packages :yum ["couchdb"]
            :aptitude ["couchdb"]))
