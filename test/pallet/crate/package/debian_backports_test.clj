(ns pallet.crate.package.debian-backports-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [package-source]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.package.debian-backports :refer [add-debian-backports]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once (logging-threshold-fixture))

(deftest debian-backports-test
  (is
   (script-no-comment=
    (first
     (build-actions
         [session {:server {:image {:os-family :debian}}
                   :phase-context "add-debian-backports"}]
       (package-source
        session
        "debian-backports"
        :aptitude {:url "http://backports.debian.org/debian-backports"
                   :release (str
                             (stevedore/script (~lib/os-version-name))
                             "-backports")
                   :scopes ["main"]})))
    (first
     (build-actions [session {:server {:image {:os-family :debian}}}]
       (add-debian-backports session))))))
