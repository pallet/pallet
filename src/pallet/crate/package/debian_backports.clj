(ns pallet.crate.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.actions :refer [package package-source repository]]
   [pallet.plan :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :refer [apply-map]]))

(defplan add-debian-backports
  "Add debian backport package repository."
  [session {:keys [url mirror release scopes]
            :or {mirror "ftp.us.debian.org"}
            :as options}]
  (package session "lsb-release")
  (package-source
   session
   "debian-backports"
   (merge
    {:url (str "http://" mirror "/debian"
               (stevedore/fragment
                @(if (= (lib/os-version-name) "squeeze")
                   ("echo" -n "-backports"))))
     :release (str
               (stevedore/fragment (lib/os-version-name))
               "-backports")
     :scopes ["main"]}
    options)))

(defmethod repository :debian-backports
  [session options]
  (add-debian-backports options))
