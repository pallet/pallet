(ns pallet.crate.package.rpmforge
  "Actions for working with the rpmforge repository"
  (:require
   [pallet.actions
    :refer [exec-checked-script package package-manager repository]]
   [pallet.actions.direct.remote-file :refer [remote-file*]]
   [pallet.plan :refer [defplan]]
   [pallet.utils :refer [apply-map]]))

(def ^{:private true}
  rpmforge-url-pattern
  "http://packages.sw.be/rpmforge-release/rpmforge-release-%s.%s.rf.%s.rpm")

;; this is an aggregate so that it can come before the aggragate package-manager
(defplan add-rpmforge
  "Add the rpmforge repository"
  [session {:keys [version distro arch]
            :or {version "0.5.2-2" distro "el5" arch "i386"}}]
  (exec-checked-script
   session
   "Add rpmforge repositories"
   (chain-or
    (if (= "0" @(pipe ("rpm" -qa) ("grep" rpmforge) ("wc" -l)))
      (do
        ~(first
          (remote-file*
           session
           "rpmforge.rpm"
           {:url (format rpmforge-url-pattern version distro arch)}))
        ("rpm" -U --quiet "rpmforge.rpm"))))))

(defmethod repository :rpmforge
  [session args]
  (add-rpmforge session args))
