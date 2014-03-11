(ns pallet.crate.package.debian-backports
  "Actions for working with the debian backports repository"
  (:require
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [package-source package repository]]
   [pallet.crate :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :refer [apply-map]]))

(defplan add-debian-backports
  "Add debian backport package repository"
  [& {:keys [url mirror release scopes]
      :or {mirror "ftp.us.debian.org"}
      :as options}]
  (with-action-options {:always-before ::backports}
    (package "lsb-release"))
  (with-action-options {:action-id ::backports}
    (package-source
     "debian-backports"
     :aptitude
     (merge
      (dissoc options :mirror)
      {:url (str "http://" mirror "/debian"
                 (stevedore/fragment
                  @(if (= (lib/os-version-name) "squeeze")
                     ("echo" -n "-backports"))))
       :release (str
                 (stevedore/fragment
                  (~lib/os-version-name)) "-backports")
       :scopes ["main"]}))))

(defmethod repository :debian-backports
  [args]
  (apply-map add-debian-backports (dissoc args :id)))
