(ns pallet.crate.package.jpackage-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.package.jpackage
    :refer [add-jpackage jpackage-utils package-manager-update-jpackage]]
   [pallet.test-utils :refer [make-node]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest jpackage-test
  (is
   (build-actions
    {:server
     {:node
      (make-node "n" :os-family :centos :os-version "5.5" :packager :yum)}}
    (jpackage-utils)
    (add-jpackage)
    (
     package-manager-update-jpackage))))
