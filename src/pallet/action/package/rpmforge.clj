(ns pallet.action.package.rpmforge
  "Actions for working with the rpmforge repository"
  (:require
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]))

(def ^{:private true}
  remote-file* (action/action-fn remote-file/remote-file-action))

(def ^{:private true}
  rpmforge-url-pattern
  "http://packages.sw.be/rpmforge-release/rpmforge-release-%s.%s.rf.%s.rpm")

;; this is an aggregate so that it can come before the aggragate package-manager
(action/def-aggregated-action add-rpmforge
  "Add the rpmforge repository"
  [session args]
  {:always-before #{`package/package-manager `package/package}
   :arglists '([session & {:keys [version distro arch]
                           :or {version "0.5.2-2" distro "el5" arch "i386"}}])}
  (let [{:keys [version distro arch]
         :or {version "0.5.2-2"
              distro "el5"
              arch "i386"}} (apply hash-map (first args))]
    (stevedore/checked-script
     "Add rpmforge repositories"
     (chain-or
      (if (= "0" @(pipe (rpm -qa) (grep rpmforge) (wc -l)))
        (do
          ~(remote-file*
            session
            "rpmforge.rpm"
            :url (format rpmforge-url-pattern version distro arch))
          ("rpm" -U --quiet "rpmforge.rpm")))))))
