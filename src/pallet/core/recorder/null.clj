(ns pallet.core.recorder.null
  "Defines a result record that discards all results."
  (:require
   [clojure.core.typed :refer [ann ann-datatype inst Atom1 Vec]]
   [pallet.core.types :refer [ActionResult Recorder]]
   [pallet.core.recorder.protocols :refer :all])
  (:import
   clojure.lang.IPersistentVector))

(ann-datatype NullRecorder [])

(deftype NullRecorder []
  Record
  (record [_ result])
  Results
  (results [_]))

(ann ^:no-check null-recorder [-> Recorder])
(defn null-recorder [] (NullRecorder.))
