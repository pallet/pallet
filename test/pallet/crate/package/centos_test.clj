(ns pallet.crate.package.centos-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [package package-source]]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.crate.package.centos :refer [add-repository]]
   [pallet.plan :refer [plan-context]]))

(use-fixtures :once (logging-threshold-fixture))

(def session {:target {:override {:os-family :centos}}})

(deftest add-repository-test
  (is
   (=
    (build-plan [session session]
      (plan-context 'add-repository
        (package session "yum-priorities")
        (package-source
         session
         "Centos 5.5 os x86_64"
         {:url
          "http://mirror.centos.org/centos/5.5/os/x86_64/repodata/repomd.xml"
          :gpgkey "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5"
          :priority 50})))
    (build-plan [session session] ;; :is-64bit true
      (add-repository session {}))))
  (is
   (=
    (build-plan [session session]
      (plan-context 'add-repository
        (package session "yum-priorities")
        (package-source
         session
         "Centos 5.4 updates i386"
         {:url
          "http://mirror.centos.org/centos/5.4/os/i386/repodata/repomd.xml"
          :gpgkey "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5"
          :priority 50})))
    (build-plan [session (assoc-in session [:target :override :is-64bit] false)]
      (add-repository session {:version "5.4" :repository "updates"})))))
