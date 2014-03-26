(ns pallet.execute-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))
