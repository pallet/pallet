(ns pallet.core.recorder.in-memory
  (:require
   [pallet.core.recorder.protocols :refer :all]))

(deftype InMemoryRecorder [results]
  Record
  (record [_ result]
    (swap! results conj result))
  Results
  (results [_] @results))

(defn in-memory-recorder
  []
  (InMemoryRecorder. (atom [])))
