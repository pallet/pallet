(ns pallet.core.recorder.protocols
  "Protocols for the action recorder"
  (:require
   [clojure.core.typed :refer [defprotocol>]]))

(defprotocol> Record
  (record [_ result] "Record a result"))

(defprotocol> Results
  (results [_] "Return recorded results"))
