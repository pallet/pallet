(ns pallet.crate.package.jpackage-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.crate.package.jpackage
    :refer [add-jpackage jpackage-utils package-manager-update-jpackage]]
   [pallet.test-utils :refer [make-node]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest jpackage-test
  (is
   (build-plan
       [session {:target
                 (make-node "n" {:os-family :centos
                                 :os-version "5.5"
                                 :packager :yum})}]
     (jpackage-utils session)
     (add-jpackage session {})
     (package-manager-update-jpackage session))))
