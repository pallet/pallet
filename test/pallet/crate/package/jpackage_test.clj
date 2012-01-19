(ns pallet.crate.package.jpackage-test
  (:use
   pallet.crate.package.jpackage
   clojure.test
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest jpackage-test
  (is
   (build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    jpackage-utils
    (add-jpackage)
    package-manager-update-jpackage)))
