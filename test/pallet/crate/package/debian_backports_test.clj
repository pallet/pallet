(ns pallet.crate.package.debian-backports-test
  (:use
   pallet.crate.package.debian-backports
   clojure.test
   [pallet.actions :only [package-source]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once (logging-threshold-fixture))

(deftest debian-backports-test
  (is
   (script-no-comment=
    (first
     (build-actions
         {:server {:image {:os-family :debian}}
          :phase-context "add-debian-backports"}
       (package-source
        "debian-backports"
        :aptitude {:url "http://backports.debian.org/debian-backports"
                   :release (str
                             (stevedore/script (~lib/os-version-name))
                             "-backports")
                   :scopes ["main"]})))
    (first
     (build-actions
         {:server {:image {:os-family :debian}}}
       (add-debian-backports))))))
