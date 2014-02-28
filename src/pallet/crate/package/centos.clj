(ns pallet.crate.package.centos
  "Actions for working with the centos repositories"
  (:require
   [pallet.actions :refer [package package-source repository]]
   [pallet.plan :refer [defplan]]
   [pallet.session :refer [target]]
   [pallet.target :refer [is-64bit?]]
   [pallet.utils :refer [apply-map]]))

(def ^{:private true} centos-repo
  "http://mirror.centos.org/centos/%s/%s/%s/repodata/repomd.xml")

(def ^{:private true} centos-repo-key
  "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-%s")

(defn ^{:doc "Return the centos package architecture for the target node."}
  arch [session]
  (if (is-64bit? (target session)) "x86_64" "i386"))

(defplan add-repository
  "Add a centos repository. By default, ensure that it has a lower than default
  priority."
  [session {:keys [version repository enabled priority]
            :or {version "5.5" repository "os" enabled 0 priority 50}}]
  (let [arch-str (arch session)]
    (package session "yum-priorities")
    (package-source
     session
     (format "Centos %s %s %s" version repository arch-str)
     {:url (format centos-repo version repository arch-str)
      :gpgkey (format centos-repo-key (str (first version)))
      :priority priority
      :enabled enabled})))

(defmethod repository :centos
  [session args]
  (add-repository session args))
