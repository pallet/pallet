(ns pallet.core.recorder.protocols
  "Protocols for the action recorder")

(defprotocol Record
  (record [_ result] "Record a result"))

(defprotocol Results
  (results [_] "Return recorded results"))
