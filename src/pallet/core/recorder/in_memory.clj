(ns pallet.core.recorder.in-memory
  (:require
   [pallet.core.recorder.protocols :refer :all]))

(deftype InMemoryRecorder [res]
  Record
  (record [_ result]
    (swap! res conj result))
  Results
  (results [_] (seq @res)))

(defn in-memory-recorder
  []
  (InMemoryRecorder. (atom (vector))))
