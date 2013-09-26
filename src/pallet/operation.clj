(ns pallet.operation)

(defprotocol Status
  "Status protocol."
  (status [_] "Return a status."))

(defprotocol Abortable
  "Protocol for something that can be aborted."
  (abort! [_ v] "Abort the operation."))

(defprotocol StatusUpdate
  "Protocol to update a status."
  (status! [_ v] "Append to the status."))

(defprotocol DeliverValue
  "Protocol to deliver a value."
  (value! [_ v] "Deliver the value."))
