(ns pallet.crate.os
  "OS detection for pallet to determine os and version"
  (:require
   [clojure.string :as string :refer [blank? lower-case]]
   [pallet.actions :refer [assoc-settings exec-script plan-when plan-when-not
                           with-action-values]]
   [pallet.crate :refer [defplan]]
   [pallet.stevedore :refer [script]]
   [pallet.utils :refer [maybe-assoc]]))

;;; NB no script functions here
(defn os-detection
  "Returns a script, that when executed, should identify the os of a unix
  target."
  []
  (script
   (println "{")
   (println "  :os" (str "'\"'" @("uname" -s) "'\"'"))
   (println "  :rev" (str "'\"'" @("uname" -r) "'\"'"))
   (println "  :mach" (str "'\"'" @("uname" -m) "'\"'"))
   (println "}")))

(defn distro-detection
  "Returns a script, that when executed, should identify distro of a linux
  target."
  []
  (script
   (when (file-exists? "/etc/debconf_version")
     (set! ID @(pipe ("cat" "/etc/redhat-release")
                     ("egrep" -o -e "'^[A-Za-z ]+release'")
                     ("sed -e 's/ release//'")))
     (set! RELEASE @("lsb_release" -s -r)))
   (when (file-exists? "/etc/lsb-release")
     ("source" "/etc/lsb-release")
     (set! ID @DISTRIB_ID)
     (set! RELEASE @DISTRIB_RELEASE))
   (when (file-exists? "/etc/redhat-release")
     (set! ID @(pipe ("cat" "/etc/redhat-release")
                     ("egrep" -o -e "'^[A-Za-z ]+release'")
                     ("sed -e 's/ .*//'")))
     (set! RELEASE @(pipe ("cat" "/etc/redhat-release")
                          ("sed" -e "'s/.*release//'")
                          ("sed" -e "'s/[^0-9.]//g'"))))
   (when (file-exists? "/etc/SUSE-release")
     (set! ID @(pipe ("cat" "/etc/SUSE-release")
                     ("tr" "\n" "' '")
                     ("sed" -e "'s/VERSION.*//'")))
     (set! RELEASE @(pipe ("cat" "/etc/SUSE-release")
                          ("tr" "\n" "' '")
                          ("sed" -e "'s/.*= //'"))))
   (when (file-exists? "/etc/mandrake-release")
     (set! ID "Mandrake")
     (set! RELEASE @(pipe ("cat" "/etc/mandrake-release")
                          ("sed" -e "'s/.*release //'")
                          ("sed" -e "'s/ .*//'"))))

   (println "{")
   (println "  :id" (str "'\"'" @ID:-unknown "'\"'"))
   (println "  :release" (str "'\"'" @RELEASE:-unknown "'\"'"))
   (println "}")))

(def pre-map-output #"(?m)[^{}]*\{")

(defplan infer-os
  "Infer the OS family and version from a node"
  []
  (let [os (exec-script (os-detection))]
    (with-action-values [os]
      (when (and (number? (:exit os)) (zero? (:exit os)))
        (let [out (string/replace-first (:out os) pre-map-output "{")
              os (read-string out)]
          (-> os
              (maybe-assoc :os-family
                           (when-not (blank? (:os os))
                             (keyword (lower-case (:os os)))))
              (maybe-assoc :os-version
                           (when-not (blank? (:rev os))
                             (lower-case (:rev os))))))))))

(defplan infer-distro
  "Infer the linux distribution from a node"
  []
  (let [distro (exec-script (distro-detection))]
    (with-action-values [distro]
      (when (and (number? (:exit distro)) (zero? (:exit distro)))
        (let [out (string/replace-first (:out distro) pre-map-output "{")
              distro (read-string out)]
          (-> distro
              (maybe-assoc :os-family
                           (when-not (blank? (:id distro))
                             (keyword (lower-case (:id distro)))))
              (maybe-assoc :os-version
                           (when-not (blank? (:release distro))
                             (lower-case (:release distro))))))))))

(defplan os
  "Infer OS and distribution.  Puts a map into the settings' :pallet/os
  facility."
  []
  (let [os (infer-os)
        distro (infer-distro)
        m (with-action-values [distro os]
            (->>
             (dissoc (merge os distro) :action-symbol :context)
             (remove #(#{:unknown "unknown"} (val %)))
             (into {})))]
    (assoc-settings :pallet/os m)))
