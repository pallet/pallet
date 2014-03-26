(ns pallet.crate.package.rpmforge-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.crate.package.rpmforge :refer [add-rpmforge]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest rpmforge-test
  (is
   (build-plan [session {:target {:override
                                  {:packager :yum
                                   :os-family :centos
                                   :os-version "5.5"}}}]
     (add-rpmforge session {}))))
