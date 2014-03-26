(ns pallet.crate.package.epel-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.crate.package.epel :refer [add-epel]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest epel-test
  (is
   (build-plan [session {:target {:override
                                  {:packager :yum
                                   :os-family :centos
                                   :os-version "5.5"}}}]
    (add-epel session {}))))
