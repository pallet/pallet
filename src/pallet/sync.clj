(ns pallet.sync
  "Phase syncronisation service.

A hierarchical phase syncronisation service that synchronises on exit
of a phase, across all targets in the parent phase."
  (:require
   [clojure.core.async :as async :refer [chan close! go thread <! <!!]]
   [clojure.tools.logging :refer [debugf]]
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
  if execution should abort.

  When called with a channel, will execute asynchronously, putting
  a value on ch when the leave can take place."
  ([sync-service phase target]
     (let [ch (chan)]
       (impl/leave-phase sync-service phase target ch)
       (let [r (<!! ch)]
         (close! ch)
         r)))
  ([sync-service phase target ch]
     (impl/leave-phase sync-service phase target ch)))

(defn abort-phase
  "Abort the current phase on the target.  `reason` must be a map.

  This should be called if the phase is aborted for some reason
  (e.g. it throws an exception)."
  [sync-service phase target reason]
  {:pre [(map? reason)]}
  (impl/abort-phase sync-service phase target reason))

(defn dump
  [sync-service]
  (impl/dump-state sync-service))

(defn sync-phase*
  "Execute function f in phase synchronisation for target"
  [sync-service phase target options f]
  (let [completion-ch (chan)]
    (go
     (try
       (if (enter-phase sync-service phase target options)
         (<! (thread
              (try
                (f)
                (catch Exception e
                  (abort-phase sync-service phase target {:exception e})
                  e)))))
       (catch Exception e
         (abort-phase sync-service phase target {:exception e})
         e)
       (finally
         (leave-phase sync-service phase target completion-ch)))
     (let [r (<! completion-ch)]
       (debugf "sync-phase* return %s" r)
       r))))

;; (defmacro sync-phase
;;   [sync-service [phase-name target options] & body]
;;   `(sync-phase* ~sync-service ~phase-name ~target ~options (fn [] ~@body)))
