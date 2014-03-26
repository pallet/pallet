(ns pallet.crate.package.debian-backports-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [package package-source]]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.crate.package.debian-backports :refer [add-debian-backports]]
   [pallet.plan :refer [plan-context]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once (logging-threshold-fixture))

(def session {:target {:override {:os-family :debian}}})

(deftest debian-backports-test
  (is
   (=
    (build-plan [session session]
      (plan-context 'add-debian-backports
        (package session "lsb-release")
        (package-source
         session
         "debian-backports"
         {:url "http://backports.debian.org/debian-backports"
          :release (str
                    (stevedore/script (lib/os-version-name))
                    "-backports")
          :scopes ["main"]})))
    (build-plan [session session]
      (add-debian-backports session {})))))
