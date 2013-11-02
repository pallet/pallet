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
  (impl/enter-phase sync-service phase targets {}))

(defn enter-phase
  "Enter the phase on the target, with no synchronisation.

  Returns a boolean which should be used to guard a block of code.

  `:guard-fn`
  : A function that will be used to guard entry into the phase.

  `:on-complete-fn`
  : A function that will be called when the phase is successfully
    completed.  Not called if guard-fn return a falsey value.

  `:leave-value-fn`
  : A function that will be called when leaving the phase to determine
    the return value.  Will be called with a single map argument, with
    values mapping from target to status map.  The status map contains
    an `:aborted` key with truthy value if the target aborted the
    phase.  By default the return value will be {:state :continue} if
    no target aborted, or {:state :abort} if any target aborted."
  [sync-service phase target
   {:keys [guard-fn on-complete-fn leave-value-fn] :as options}]
  (impl/enter-phase sync-service phase [target] options))

(defn leave-phase
  "Leave the phase on the target.
  Synchronises across all targets in the parent phase.

  Must be called, even if enter-phase returned false or abort-phase
  was called (should probably be called inside a finally clause for a
  try around the phase body).

  Return {:state :continue} if execution can continue, {:state :abort}
  if execution should abort."
  [sync-service phase target]
  (let [ch (chan)]
    (impl/leave-phase sync-service phase target ch)
    (let [r (<!! ch)]
      (close! ch) r)))

(defn abort-phase
  "Abort the current phase on the target.

  This should be called if the phase is aborted for some reason (e.g. it throws
  an exception)."
  [sync-service phase target]
  (impl/abort-phase sync-service phase target))

(defn dump
  [sync-service]
  (impl/dump-state sync-service))
