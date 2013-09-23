(ns pallet.operation)

(defprotocol Status
  "Status protocol."
  (status [_] "Return a status."))

(defprotocol Abortable
  "Protocol for something that can be aborted."
  (abort! [_ v] "Abort the operation."))
