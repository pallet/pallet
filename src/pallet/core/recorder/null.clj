(ns pallet.core.recorder.null
  "Defines a result record that discards all results."
  (:require
   [pallet.core.recorder.protocols :refer :all]))

(deftype NullRecorder []
  Record
  (record [_ result])
  Results
  (results [_]))

(defn null-recorder [] (NullRecorder.))
