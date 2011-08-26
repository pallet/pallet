(ns pallet.action.package.jpackage
  "Actions for working with the jpackage repository"
  (:require
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.thread-expr :as thread-expr]))

;; The source for this rpm is available here:
;; http://plone.lucidsolutions.co.nz/linux/centos/
;;   jpackage-rpm-repository-for-centos-rhel-5.x
;; http://plone.lucidsolutions.co.nz/linux/centos/images/
;;   jpackage-utils-compat-el5-0.0.1-1.noarch.rpm/at_download/file
(def jpackage-utils-compat-rpm
  (str "https://github.com/downloads/pallet/pallet/"
       "jpackage-utils-compat-el5-0.0.1-1.noarch.rpm"))

(defn jpackage-utils
  "Add jpackge-utils. Due to incompatibilities on RHEL derived distributions,
   a compatability package is required.

   https://bugzilla.redhat.com/show_bug.cgi?id=260161
   https://bugzilla.redhat.com/show_bug.cgi?id=497213"
  [session]
  (->
   session
   (thread-expr/when->
    (or
     (= :fedora (session/os-family session))
     (and
      (#{:rhel :centos} (session/os-family session))
      (re-matches #"5\.[0-5]" (session/os-version session))))
    (action/with-precedence {:action-id ::install-jpackage-compat}
      (package/add-rpm
       "jpackage-utils-compat-el5-0.0.1-1"
       :url jpackage-utils-compat-rpm
       :insecure true))) ;; github's ssl doesn't validate
   (package/package "jpackage-utils")))

(def jpackage-mirror-fmt
  "http://www.jpackage.org/mirrorlist.php?dist=%s&type=%s&release=%s")

(defn mirrorlist
  [dist type release]
  (format jpackage-mirror-fmt dist type release))

(defn add-jpackage
  "Add the jpackage repository.  component should be one of:
     fedora
     redhat-el

   Installs the jpackage-utils package from the base repos at a
   pritority of 25."
  [session & {:keys [version component releasever enabled]
              :or {component "redhat-el"
                   releasever "$releasever"
                   version "5.0"
                   enabled 0}}]
  (let [no-updates (and                 ; missing updates for fedora 13, 14
                    (= version "5.0")
                    (= :fedora (session/os-family session))
                    (try
                      (< 12 (Integer/decode
                             (str (session/os-version session))))
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
    (->
     session
     (package/package-source
      "jpackage-generic"
      :yum {:mirrorlist (mirrorlist "generic" "free" version)
            :failovermethod "priority"
            ;;gpgkey "http://www.jpackage.org/jpackage.asc"
            :enabled enabled})
     (package/package-source
      "jpackage-generic-non-free"
      :yum {:mirrorlist (mirrorlist "generic" "non-free" version)
            :failovermethod "priority"
            ;;gpgkey "http://www.jpackage.org/jpackage.asc"
            :enabled enabled})
     (package/package-source
      (format "jpackage-%s" component)
      :yum {:mirrorlist (mirrorlist
                         (str component "-" releasever) "free" version)
            :failovermethod "priority"
            ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
            :enabled enabled})
     (package/package-source
      "jpackage-generic-updates"
      :yum {:mirrorlist (mirrorlist "generic" "free" (str version "-updates"))
            :failovermethod "priority"
            ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
            :enabled enabled})
     (package/package-source
      "jpackage-generic-updates-non-free"
      :yum {:mirrorlist (mirrorlist
                         "generic" "non-free" (str version "-updates"))
            :failovermethod "priority"
            ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
            :enabled enabled})
     (thread-expr/when-not->
      no-updates
      (package/package-source
       (format "jpackage-%s-updates" component)
       :yum {:mirrorlist (mirrorlist
                          (str component "-" releasever)
                          "free"
                          (str version "-updates"))
             :failovermethod "priority"
             ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
             :enabled enabled}))
     (parameter/assoc-for-target [:jpackage-repos] jpackage-repos))))

(defn package-manager-update-jpackage
  "Update the package lists for the jpackage repositories"
  [request]
  (package/package-manager
   request :update
   :disable ["*"]
   :enable (parameter/get-for-target request [:jpackage-repos])))
