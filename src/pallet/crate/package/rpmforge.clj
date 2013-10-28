(ns pallet.crate.package.rpmforge
  "Actions for working with the rpmforge repository"
  (:require
   [pallet.action :refer [action-fn]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions
    :refer [exec-checked-script package package-manager repository]]
   [pallet.actions.decl :refer [remote-file-action]]
   [pallet.crate :refer [defplan]]
   [pallet.utils :refer [apply-map]]))

;;; TODO remove this and use plan-when
(def ^{:private true}
  remote-file* (action-fn remote-file-action :direct))

(def ^{:private true}
  rpmforge-url-pattern
  "http://packages.sw.be/rpmforge-release/rpmforge-release-%s.%s.rf.%s.rpm")

;; this is an aggregate so that it can come before the aggragate package-manager
(defplan add-rpmforge
  "Add the rpmforge repository"
  [session & {:keys [version distro arch]
              :or {version "0.5.2-2" distro "el5" arch "i386"}}]
  (with-action-options session {:always-before #{package-manager package}}
    (exec-checked-script
     session
     "Add rpmforge repositories"
     (chain-or
      (if (= "0" @(pipe ("rpm" -qa) ("grep" rpmforge) ("wc" -l)))
        (do
          ~(first
            (remote-file*
             "rpmforge.rpm"
             {:url (format rpmforge-url-pattern version distro arch)}))
          ("rpm" -U --quiet "rpmforge.rpm")))))))

(defmethod repository :rpmforge
  [args]
  (apply-map add-rpmforge args))
