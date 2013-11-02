(ns pallet.sync
  "Phase syncronisation service.

A hierarchical phase syncronisation service that synchronises on exit
of a phase, across all targets in the parent phase."
  (:require
   [clojure.core.async :as async :refer [chan close! <!!]]
   [pallet.sync.protocols :as impl]))

(defn enter-phase-targets
  "Enter phase on all the targets, with no synchronisation.
  The phase must be at a single hierarchical level across all targets.

  Returns the previous phase.

  Throws an exception with :op :phase-sync/enter-phase, and :reason
  :multiple-phase-vectors if the phase vector is not unique across
  targets.  The exception contains the following keys: :targets,
  :phase-vectors."
  [sync-service phase targets]
  (impl/enter-phase sync-service phase targets))

(defn enter-phase
  "Enter the phase on the target, with no synchronisation.

  Returns the previous phase."
  [sync-service phase target]
  (impl/enter-phase sync-service phase [target]))

(defn leave-phase
  "Leave the phase on the target.
  Synchronises across all targets in the parent phase.

  Return {:state :continue} if execution can continue."
  [sync-service phase target]
  (let [ch (chan)]
    (impl/leave-phase sync-service phase target ch)
    (let [r (<!! ch)]
      (close! ch)
      r)))

(defn dump
  [sync-service]
  (impl/dump-state sync-service))
