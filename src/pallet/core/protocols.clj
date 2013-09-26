(ns pallet.core.protocols
  (:require [clojure.core.async.impl.protocols :refer [Channel]]))

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

(defn operation? [x]
  (and (satisfies? Status x)
       (satisfies? StatusUpdate x)
       (satisfies? DeliverValue x)))

(defn channel? [x]
  (satisfies? Channel x))
