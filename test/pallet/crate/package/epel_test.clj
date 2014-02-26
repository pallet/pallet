(ns pallet.crate.package.epel-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.package.epel :refer [add-epel]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest epel-test
  (is
   (build-actions [session {:server
                            {:packager :yum
                             :image {:os-family :centos :os-version "5.5"}}}]
    (add-epel session))))
