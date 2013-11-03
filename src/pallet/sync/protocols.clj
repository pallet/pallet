(ns pallet.sync.protocols
  "Protocols for the phase synchronisation service")

(defprotocol SyncService
  "A synchronisation service for phases"
  (enter-phase [_ phase targets options]
    "Enter phase on all the targets.  Does not synchronise.")
  (leave-phase [_ phase target synch-ch]
    "Leave the phase on the target.
    Synchronises across all targets in the parent phase.")
  (abort-phase [_ phase target reason]
    "Abort execution of the current phase on the target"))

(defprotocol StateDumper
  (dump-state [_] "Dump internal state"))
