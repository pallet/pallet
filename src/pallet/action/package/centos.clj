(ns pallet.action.package.centos
  "Actions for working with the centos repositories"
  (:require
   [pallet.action.package :as package]
   [pallet.parameter :as parameter]
   [pallet.session :as session]))

(def ^{:private true} centos-repo
  "http://mirror.centos.org/centos/%s/%s/%s/repodata/repomd.xml")

(def ^{:private true} centos-repo-key
  "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-%s")

(defn arch
  "Return the centos package architecture for the target node."
  [session]
  (if (session/is-64bit? session) "x86_64" "i386"))

(defn add-repository
  "Add a centos repository. By default, ensure that it has a lower than default
  priority."
  [session & {:keys [version repository enabled priority]
              :or {version "5.5" repository "os" enabled 0 priority 50}}]
  (->
   session
   (package/package "yum-priorities")
   (package/package-source
    (format "Centos %s %s %s" version repository (arch session))
    :yum {:url (format centos-repo version repository (arch session))
          :gpgkey (format centos-repo-key (str (first version)))
          :priority priority
          :enabled enabled})))
