(ns pallet.core.recorder.in-memory
  (:require
   [clojure.core.typed :refer [ann ann-datatype inst Atom1 Vec]]
   [pallet.core.types :refer [ActionResult Recorder]]
   [pallet.core.recorder.protocols :refer :all])
  (:import
   clojure.lang.IPersistentVector))

(ann-datatype InMemoryRecorder
              [res :- (Atom1 (IPersistentVector ActionResult))])

(deftype InMemoryRecorder [res]
  Record
  (record [_ result]
    (swap! ; (inst swap! (Vec ActionResult) (Vec ActionResult) ActionResult)
     res (inst conj ActionResult ActionResult) result))
  Results
  (results [_] (seq @res)))

(ann ^:no-check in-memory-recorder [-> Recorder])
(defn in-memory-recorder
  []
  (InMemoryRecorder. (atom (vector))))
