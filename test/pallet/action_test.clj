(ns pallet.action-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))
