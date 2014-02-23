(ns pallet.core.executor.plan
  "An action executor that creates an action plan."
  (:require
   [pallet.core.executor.protocols :refer :all]))

(deftype PlanActionExecutor [actions]
  ActionExecutor
  (execute [executor target action]
    (let [rv {:target target
              :result action}]
      (swap! actions conj rv)
      rv)))

(defn executor
  "Return a plan executor"
  []
  (PlanActionExecutor. (atom [])))

(defn plan
  [^PlanActionExecutor executor]
  @(.actions executor))
