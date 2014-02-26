(ns pallet.execute-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.node :refer [id]]))

(use-fixtures :once (logging-threshold-fixture))
