(ns pallet.crate.package.epel
  "Actions for working with the epel repository"
  (:require
   [pallet.actions
    :refer [exec-checked-script package package-manager repository]]
   [pallet.plan :refer [defplan]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map]]))

(defplan add-epel
  "Add the EPEL repository"
  [session {:keys [version] :or {version "5-4"}}]
  (exec-checked-script
   session
   "Add EPEL package repository"
   ("rpm"
    -U --quiet
    ~(format
      "http://download.fedoraproject.org/pub/epel/%s/%s/epel-release-%s.noarch.rpm"
      (first version)
      (fragment @(pipe ("uname" -i) ("sed" "s/\\d86/386/")))
      version))))

(defmethod repository :epel
  [session options]
  (add-epel session options))
