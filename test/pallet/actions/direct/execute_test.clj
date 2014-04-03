(ns pallet.actions.direct.execute-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.actions.direct.execute]))

(use-fixtures :once (logging-threshold-fixture))
