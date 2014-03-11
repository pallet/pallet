(ns pallet.kb
 "A knowledge base for pallet"
 (:require
  [pallet.core.version-dispatch :refer [version-map]]
  [pallet.utils :refer [maybe-assoc]]
  [pallet.versions :refer [as-version-vector]]))

;;; Hierarchies

;; TODO fix the no-check when derive has correct annotations
(def os-hierarchy
  (-> (make-hierarchy)
      (derive :linux :os)

      ;; base distibutions
      (derive :rh-base :linux)
      (derive :debian-base :linux)
      (derive :arch-base :linux)
      (derive :suse-base :linux)
      (derive :bsd-base :linux)
      (derive :gentoo-base :linux)

      ;; distibutions
      (derive :centos :rh-base)
      (derive :rhel :rh-base)
      (derive :amzn-linux :rh-base)
      (derive :fedora :rh-base)

      (derive :debian :debian-base)
      (derive :ubuntu :debian-base)
      (derive :jeos :debian-base)

      (derive :suse :suse-base)
      (derive :arch :arch-base)
      (derive :gentoo :gentoo-base)
      (derive :darwin :bsd-base)
      (derive :os-x :bsd-base)))

;;; target mapping
(def packager-map
  (version-map os-hierarchy :os :os-version
               {{:os :debian-base} :apt
                {:os :rh-base} :yum
                {:os :arch-base} :pacman
                {:os :gentoo-base} :portage
                {:os :suse-base} :zypper
                {:os :os-x} :brew
                {:os :darwin} :brew}))

(defn packager-for-os
  "Package manager"
  [os-family os-version]
  {:pre [(keyword? os-family)]}
  (or
   (get packager-map (maybe-assoc
                      {:os os-family}
                      :os-version (and os-version
                                       (as-version-vector os-version))))
   (throw
    (ex-info
     (format "Unknown packager for %s %s" os-family os-version)
     {:type :unknown-packager}))))

(defn admin-group
  "Default admin group for host"
  [os-family os-version]
  (case os-family
    :centos "wheel"
    :rhel "wheel"
    "adm"))
