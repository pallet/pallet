(ns pallet.crate.package.rpmforge-test
  (:use
   pallet.crate.package.rpmforge
   clojure.test
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest rpmforge-test
  (is
   (build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (add-rpmforge))))
