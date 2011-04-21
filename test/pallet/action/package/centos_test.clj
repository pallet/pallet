(ns pallet.action.package.centos-test
  (:use
   pallet.action.package.centos
   clojure.test)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action.package :as package]))

(deftest add-repository-test
  (is
   (=
    (first
     (build-actions/build-actions
      {}
      (package/package "yum-priorities")
      (package/package-source
       "Centos 5.5 os x86_64"
       :yum {:url
             "http://mirror.centos.org/centos/5.5/os/x86_64/repodata/repomd.xml"
             :gpgkey "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5"
             :priority 50})))
    (first
     (build-actions/build-actions
      {:is-64bit true}
      (add-repository)))))
  (is
   (=
    (first
     (build-actions/build-actions
      {}
      (package/package "yum-priorities")
      (package/package-source
       "Centos 5.4 updates i386"
       :yum {:url
             "http://mirror.centos.org/centos/5.4/os/i386/repodata/repomd.xml"
             :gpgkey "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5"
             :priority 50})))
    (first
     (build-actions/build-actions
      {:is-64bit false}
      (add-repository :version "5.4" :repository "updates"))))))
