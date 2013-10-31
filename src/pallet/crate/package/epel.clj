(ns pallet.crate.package.epel
  "Actions for working with the epel repository"
  (:require
   [pallet.action :refer [with-action-options]]
   [pallet.actions
    :refer [exec-checked-script package package-manager repository]]
   [pallet.crate :refer [defplan]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map]]))

(defplan add-epel
  "Add the EPEL repository"
  [& {:keys [version] :or {version "5-4"}}]
  (with-action-options {:always-before #{package-manager package}}
    (exec-checked-script
     "Add EPEL package repository"
     ("rpm"
      -U --quiet
      ~(format
        "http://download.fedoraproject.org/pub/epel/%s/%s/epel-release-%s.noarch.rpm"
        (first version)
        (fragment @(pipe ("uname" -i) ("sed" "s/\\d86/386/")))
        version)))))

(defmethod repository :epel
  [args]
  (apply-map add-epel args))
