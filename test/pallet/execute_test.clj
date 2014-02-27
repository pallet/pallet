(ns pallet.execute-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))
