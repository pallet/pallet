(ns pallet.core.recorder
  "Record action results"
  (:require
   [pallet.core.recorder.protocols :as impl :refer [Record]]))

;;; # Type predicate
(defn recorder?
  "Predicate for checking the type of a plan-state."
  [recorder]
  (impl/recorder? recorder))

;;; # Operations
(defn record
  "Record a result.  Return value is unspecified."
  [recorder result]
  (impl/record recorder result))

(defn results
  "Return all recorded results"
  [recorder]
  (impl/results recorder))
