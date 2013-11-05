(ns pallet.core.recorder
  "Record action results"
  (:require
   [pallet.core.recorder.protocols :as impl]))

(defn record
  "Record a result"
  [recorder result]
  (impl/record recorder result))

(defn results
  "Return all recorded results"
  [recorder]
  (impl/results recorder))
