(ns pallet.core.executor.plan
  "An action executor that creates an action plan."
  (:require
   [pallet.core.executor.protocols :refer :all]))

(defmulti action-result
  "Compute the plan result for the plan action.  This is an extension
  mechanism, which is useful in testing, for example."
  (fn [target action]
    (-> action :action :action-symbol)))

(defn replace-action-with-symbol
  [action]
  (update-in action [:action] :action-symbol))

(def ^:dynamic *plan-result-fns*
  "A map of plan result functions for action ids"
  {})

(defmacro with-plan-result-fns
  "Execute a block of code with prescribed plan result functions."
  [m & body]
  `(binding [*plan-result-fns* ~m]
     ~@body))

(defn plan-result-f
  "Return a plan result function for an action id"
  [action-id]
  (if action-id
    (action-id *plan-result-fns*)))

(defn add-plan-result
  [action]
  (if-let [f (plan-result-f (-> action :options :action-id))]
    (f action)
    action))

(defmethod action-result :default
  [target action]
  (-> action
      replace-action-with-symbol
      add-plan-result))

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
