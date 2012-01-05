(ns pallet.action.package.debian-backports-test
  (:use
   pallet.action.package.debian-backports
   clojure.test
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once (logging-threshold-fixture))

(deftest debian-backports-test
  (is
   (=
    (first
     (pallet.context/with-phase-context
       {:kw :add-repository :msg "add-debian-backports"}
       (build-actions/build-actions
        {:server {:image {:os-family :debian}}}
        (package/package-source
         "debian-backports"
         :aptitude {:url "http://backports.debian.org/debian-backports"
                    :release (str
                              (stevedore/script (~lib/os-version-name))
                              "-backports")
                    :scopes ["main"]}))))
    (first
     (build-actions/build-actions
      {:server {:image {:os-family :debian}}}
      add-debian-backports)))))
