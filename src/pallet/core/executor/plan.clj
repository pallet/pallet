(ns pallet.core.executor.plan
  "An action executor that creates an action plan."
  (:require
   [pallet.core.executor.protocols :refer :all]))

(defmulti action-result
  "Compute the plan result for the plan action."
  (fn [target action]
    (-> action :action :action-symbol)))

(defn replace-action-with-symbol
  [action]
  (update-in action [:action] :action-symbol))

(defmethod action-result :default
  [target action]
  (replace-action-with-symbol action))

(deftype PlanActionExecutor [actions]
  ActionExecutor
  (execute [executor target action]
    (let [[rv e] (try
                   [(action-result target action)]
                   (catch Exception e
                     (if-let [result (:result (ex-data e))]
                       [result e]
                       [::no-result e])))]
      (if (not= rv ::no-result)
        (swap! actions conj rv))
      (when e (throw e))
      rv)))

(defn plan-executor
  "Return a plan executor"
  []
  (PlanActionExecutor. (atom [])))

(defn plan
  [^PlanActionExecutor executor]
  @(.actions executor))
