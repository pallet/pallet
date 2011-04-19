(ns pallet.action.package.epel
  "Actions for working with the epel repository"
  (:require
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]))

;; this is an aggregate so that it can come before the aggragate package-manager
(action/def-aggregated-action add-epel
  "Add the EPEL repository"
  [session args]
  {:arglists '([session & {:keys [version] :or {version "5-4"}}])
   :always-before #{`package/package-manager `package/package}}
  (let [{:keys [version] :or {version "5-4"}} (apply
                                               merge {}
                                               (map #(apply hash-map %) args))]
    (stevedore/script
     ;; "Add EPEL package repository"
     ("rpm"
      -U --quiet
      ~(format
        "http://download.fedora.redhat.com/pub/epel/5/%s/epel-release-%s.noarch.rpm"
        "$(uname -i)"
        version)))))
