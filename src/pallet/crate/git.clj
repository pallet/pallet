(ns pallet.crate.git
  (:use
   [pallet.resource.package :only [packages]]))


(defn git
  "Install git"
  [request]
  (packages request
            :yum ["git" "git-email"]
            :aptitude ["git-core" "git-email"]))
