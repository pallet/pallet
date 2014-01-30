(ns pallet.core.executor
  "Action Execution"
  (:require
   [pallet.core.executor.protocols :as impl]))

(defn execute
  "Execute an action on a target, using any action-options specified.

The executor can report an error by throwing an exception, in which
case no result value is available for the action, unless the thrown
exception has a :result key in it's ex-data, in which case that is
recorded as the result, before re-throwing the exception."
  [executor target
  user action]
  (impl/execute executor target user action))

(defn executor?
  "Predicate to test for an executor"
  [x]
  (satisfies? impl/ActionExecutor x))
