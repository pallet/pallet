(ns pallet.crate.package.jpackage
  "Actions for working with the jpackage repository"
  (:require
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions
    :refer [add-rpm
            package
            package-manager
            package-source
            repository]]
   [pallet.plan :refer [defplan]]
   [pallet.settings :refer [assoc-settings get-settings]]
   [pallet.target :refer [os-family os-version]]
   [pallet.utils :refer [apply-map]]))

;; The source for this rpm is available here:
;; http://plone.lucidsolutions.co.nz/linux/centos/
;;   jpackage-rpm-repository-for-centos-rhel-5.x
;; http://plone.lucidsolutions.co.nz/linux/centos/images/
;;   jpackage-utils-compat-el5-0.0.1-1.noarch.rpm/at_download/file
(def jpackage-utils-compat-rpm
  (str "https://github.com/downloads/pallet/pallet/"
       "jpackage-utils-compat-el5-0.0.1-1.noarch.rpm"))

(defplan jpackage-utils
  "Add jpackge-utils. Due to incompatibilities on RHEL derived distributions,
   a compatability package is required.

   https://bugzilla.redhat.com/show_bug.cgi?id=260161
   https://bugzilla.redhat.com/show_bug.cgi?id=497213"
  [session]
  (let [os-family (os-family session)
        os-version (os-version session)]
    (when (or
           (= :fedora os-family)
           (and
            (#{:rhel :centos} os-family)
            (re-matches #"5\.[0-5]" os-version)))
      (with-action-options session {:action-id ::install-jpackage-compat}
        (add-rpm
         "jpackage-utils-compat-el5-0.0.1-1"
         :url jpackage-utils-compat-rpm
         :insecure true)))) ;; github's ssl doesn't validate
  (package session "jpackage-utils"))

(def jpackage-mirror-fmt
  "http://www.jpackage.org/mirrorlist.php?dist=%s&type=%s&release=%s")

(defn mirrorlist
  [dist type release]
  (format jpackage-mirror-fmt dist type release))

(defplan add-jpackage
  "Add the jpackage repository.  component should be one of:
     fedora
     redhat-el

   Installs the jpackage-utils package from the base repos at a
   priority of 25."
  [session & {:keys [version component releasever enabled]
              :or {component "redhat-el"
                   releasever "$releasever"
                   version "5.0"
                   enabled 0}}]
  (let [os-family (os-family session)
        os-version (os-version session)
        no-updates (and            ; missing updates for fedora 13, 14
                    (= version "5.0")
                    (= :fedora os-family)
                    (try
                      (< 12 (Integer/decode (str os-version)))
                      (catch NumberFormatException _)))
        jpackage-repos (vec
                        (filter
                         identity
                         ["jpackage-generic"
                          "jpackage-generic-updates"
                          "jpackage-generic-non-free"
                          "jpackage-generic-updates-non-free"
                          (format "jpackage-%s" component)
                          (when-not no-updates
                            (format "jpackage-%s-updates" component))]))]
    (package-source
     session
     "jpackage-generic"
     {:mirrorlist (mirrorlist "generic" "free" version)
      :failovermethod "priority"
      ;;gpgkey "http://www.jpackage.org/jpackage.asc"
      :enabled enabled})
    (package-source
     session
     "jpackage-generic-non-free"
     {:mirrorlist (mirrorlist "generic" "non-free" version)
      :failovermethod "priority"
      ;;gpgkey "http://www.jpackage.org/jpackage.asc"
      :enabled enabled})
    (package-source
     session
     (format "jpackage-%s" component)
     {:mirrorlist (mirrorlist
                   (str component "-" releasever) "free" version)
      :failovermethod "priority"
      ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
      :enabled enabled})
    (package-source
     session
     "jpackage-generic-updates"
     {:mirrorlist (mirrorlist "generic" "free" (str version "-updates"))
      :failovermethod "priority"
      ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
      :enabled enabled})
    (package-source
     session
     "jpackage-generic-updates-non-free"
     {:mirrorlist (mirrorlist
                   "generic" "non-free" (str version "-updates"))
      :failovermethod "priority"
      ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
      :enabled enabled})
    (when-not no-updates
      (package-source
       session
       (format "jpackage-%s-updates" component)
       {:mirrorlist (mirrorlist
                     (str component "-" releasever)
                     "free"
                     (str version "-updates"))
        :failovermethod "priority"
        ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
        :enabled enabled}))
    (assoc-settings session :jpackage-repos {:repos jpackage-repos})))

(defplan package-manager-update-jpackage
  "Update the package lists for the jpackage repositories"
  [session]
  (let [{:keys [repos]} (get-settings session :jpackage-repos)]
    (package-manager
     session
     :update
     {:disable ["*"]
      :enable repos})))

(defmethod repository :jpackage
  [args]
  (apply-map add-jpackage args))
