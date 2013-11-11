(ns pallet.core.recorder
  "Record action results"
  (:require
   [clojure.core.typed :refer [ann Nilable NonEmptySeqable]]
   [pallet.core.types :refer [ActionResult]]
   [pallet.core.recorder.protocols :as impl :refer [Record]]))

;;; # Type predicate
(ann ^:no-check recorder? (predicate pallet.core.recorder.protocols.Record))
(defn recorder?
  "Predicate for checking the type of a plan-state."
  [recorder]
  (satisfies? pallet.core.recorder.protocols/Record recorder))

;;; # Operations
(ann record [impl/Record ActionResult -> Any])
(defn record
  "Record a result.  Return value is unspecified."
  [recorder result]
  (impl/record recorder result))

(ann results [impl/Results -> (Nilable (NonEmptySeqable ActionResult))])
(defn results
  "Return all recorded results"
  [recorder]
  (impl/results recorder))
