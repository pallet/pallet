(ns pallet.core.executor
  "Action Execution"
  (:require
   [pallet.core.executor.protocols :as impl]))

(defn execute
  "Execute an action on a target, using any action-options specified"
  [executor target user action]
  (impl/execute executor target user action))

(defn executor?
  "Predicate to test for an executor"
  [x]
  (satisfies? impl/ActionExecutor x))
