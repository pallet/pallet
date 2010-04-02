(ns pallet.crate.git
  (:use
   [pallet.resource.package :only [packages]]))


(defn git
  "Install git"
  []
  (packages :yum ["git" "git-email"]
            :aptitude ["git-core" "git-email"]))
