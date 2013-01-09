(ns pallet.crate.package.jpackage-test
  (:use
   pallet.crate.package.jpackage
   clojure.test
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.test-utils :only [make-node]]))

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
