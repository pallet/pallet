(ns pallet.crate.package.epel
  "Actions for working with the epel repository"
  (:require
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [exec-checked-script package package-manager]]
   [pallet.crate :refer [defplan]]))

(defplan add-epel
  "Add the EPEL repository"
  [& {:keys [version] :or {version "5-4"}}]
  (with-action-options {:always-before #{package-manager package}}
    (exec-checked-script
     "Add EPEL package repository"
     ("rpm"
      -U --quiet
      ~(format
        "http://download.fedora.redhat.com/pub/epel/5/%s/epel-release-%s.noarch.rpm"
        "$(uname -i)"
        version)))))
