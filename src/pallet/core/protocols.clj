(ns pallet.core.protocols
  (:require
   [clojure.core.async.impl.protocols :refer [Channel]]
   [clojure.core.typed :refer [defprotocol>]]
   [pallet.core.type-annotations]))

;;; # General
(defprotocol> Closeable
  "Closeable protocol."
  (close [_] "Release acquired resources."))

;;; # Operations
(defprotocol> Status
  "Status protocol."
  (status [_] "Return a status."))

(defprotocol> Abortable
  "Protocol for something that can be aborted."
  (abort! [_ v] "Abort the operation."))

(defprotocol> StatusUpdate
  "Protocol to update a status."
  (status! [_ v] "Append to the status."))

(defprotocol> DeliverValue
  "Protocol to deliver a value."
  (value! [_ v] "Deliver the value."))

(defn operation? [x]
  (and (satisfies? Status x)
       (satisfies? StatusUpdate x)
       (satisfies? DeliverValue x)))



;;; # Helpers for external protocols
(defn ^:no-check channel? [x]
  (satisfies? Channel x))




;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (defprotocol> 1)(ann-protocol 1))
;; End:
