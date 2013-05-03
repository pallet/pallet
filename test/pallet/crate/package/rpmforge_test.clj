(ns pallet.crate.package.rpmforge-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.package.rpmforge :refer [add-rpmforge]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest rpmforge-test
  (is
   (build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (add-rpmforge))))
