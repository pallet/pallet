(ns pallet.crate.package.jpackage
  "Actions for working with the jpackage repository"
  (:use
   [pallet.action :only [with-action-options]]
   [pallet.actions :only [add-rpm package package-manager package-source
                          pipeline-when pipeline-when-not]]
   [pallet.crate :only [def-plan-fn defplan assoc-settings get-settings
                        os-family os-version]]))

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
  [os-family os-family
   os-version os-version]
  (pipeline-when
      (or
       (= :fedora os-family)
       (and
        (#{:rhel :centos} os-family)
        (re-matches #"5\.[0-5]" os-version)))
    (with-action-options {:action-id ::install-jpackage-compat}
      (add-rpm
       "jpackage-utils-compat-el5-0.0.1-1"
       :url jpackage-utils-compat-rpm
       :insecure true))) ;; github's ssl doesn't validate
  (package "jpackage-utils"))

(def jpackage-mirror-fmt
  "http://www.jpackage.org/mirrorlist.php?dist=%s&type=%s&release=%s")

(defn mirrorlist
  [dist type release]
  (format jpackage-mirror-fmt dist type release))

(def-plan-fn add-jpackage
  "Add the jpackage repository.  component should be one of:
     fedora
     redhat-el

   Installs the jpackage-utils package from the base repos at a
   pritority of 25."
  [& {:keys [version component releasever enabled]
      :or {component "redhat-el"
           releasever "$releasever"
           version "5.0"
           enabled 0}}]
  [os-family os-family
   os-version os-version
   no-updates (m-result (and            ; missing updates for fedora 13, 14
                         (= version "5.0")
                         (= :fedora os-family)
                         (try
                           (< 12 (Integer/decode (str os-version)))
                           (catch NumberFormatException _))))
   jpackage-repos (m-result (vec
                             (filter
                              identity
                              ["jpackage-generic"
                               "jpackage-generic-updates"
                               "jpackage-generic-non-free"
                               "jpackage-generic-updates-non-free"
                               (format "jpackage-%s" component)
                               (when-not no-updates
                                 (format "jpackage-%s-updates" component))])))]
  (package-source
   "jpackage-generic"
   :yum {:mirrorlist (mirrorlist "generic" "free" version)
         :failovermethod "priority"
         ;;gpgkey "http://www.jpackage.org/jpackage.asc"
         :enabled enabled})
  (package-source
   "jpackage-generic-non-free"
   :yum {:mirrorlist (mirrorlist "generic" "non-free" version)
         :failovermethod "priority"
         ;;gpgkey "http://www.jpackage.org/jpackage.asc"
         :enabled enabled})
  (package-source
   (format "jpackage-%s" component)
   :yum {:mirrorlist (mirrorlist
                      (str component "-" releasever) "free" version)
         :failovermethod "priority"
         ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
         :enabled enabled})
  (package-source
   "jpackage-generic-updates"
   :yum {:mirrorlist (mirrorlist "generic" "free" (str version "-updates"))
         :failovermethod "priority"
         ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
         :enabled enabled})
  (package-source
   "jpackage-generic-updates-non-free"
   :yum {:mirrorlist (mirrorlist
                      "generic" "non-free" (str version "-updates"))
         :failovermethod "priority"
         ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
         :enabled enabled})
  (pipeline-when-not no-updates
    (package-source
     (format "jpackage-%s-updates" component)
     :yum {:mirrorlist (mirrorlist
                        (str component "-" releasever)
                        "free"
                        (str version "-updates"))
           :failovermethod "priority"
           ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
           :enabled enabled}))
  (assoc-settings :jpackage-repos {:repos jpackage-repos}))

(defplan package-manager-update-jpackage
  "Update the package lists for the jpackage repositories"
  [{:keys [repos]} (get-settings :jpackage-repos)]
  (package-manager
   :update
   :disable ["*"]
   :enable repos))
