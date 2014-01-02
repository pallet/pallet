(ns pallet.core.executor.protocols
  "Action execution protocols")

;; should we pass target and session, or user options, or …
;; Are the user options global over executors? (I think so)
(defprotocol ActionExecutor
  (execute [_ target user action]
    "Execute an action on a target using the credentials in user"))
