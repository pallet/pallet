(ns pallet.crate.package.epel
  "Actions for working with the epel repository"
  (:require
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.action :only [with-action-options]]
   [pallet.actions :only [exec-checked-script package package-manager]]
   [pallet.monad :only [phase-pipeline-no-context]]
   [pallet.phase :only [def-plan-fn]]))

(def-plan-fn add-epel
  "Add the EPEL repository"
  [& {:keys [version] :or {version "5-4"}}]
  (with-action-options {:always-before #{package-manager package}}
    (phase-pipeline-no-context add-rpmforge {}
      (exec-checked-script
       "Add EPEL package repository"
       ("rpm"
        -U --quiet
        ~(format
          "http://download.fedora.redhat.com/pub/epel/5/%s/epel-release-%s.noarch.rpm"
          "$(uname -i)"
          version))))))
