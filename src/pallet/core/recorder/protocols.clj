(ns pallet.core.recorder.protocols
  "Protocols for the action recorder")

(defprotocol Record
  (record [_ result] "Record a result"))

(defprotocol Results
  (results [_] "Return recorded results"))

(defn recorder?
  "Predicate for checking the type of a plan-state."
  [recorder]
  (satisfies? Record recorder))
