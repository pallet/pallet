(ns pallet.core.recorder.juxt
  "A recorder that forwards to a sequence of other recorders."
  (:require
   [pallet.core.recorder.protocols :refer :all]))

(deftype JuxtRecorder [recorders]
  Record
  (record [_ result]
    (doseq [recorder recorders]
      (record recorder result)))
  Results
  (results [_] (throw (ex-info "JuxtRecorder does not implement results"
                               {:op :results/results
                                :reason :not-implemented}))))

(defn juxt-recorder
  [recorders]
  (JuxtRecorder. recorders))
