(ns pallet.core.plan-state.in-memory
  "An in-memory implementation of a plan state"
  (:require
   [pallet.core.plan-state.protocols :as impl]
   [pallet.core.plan-state :refer [scope-comparator]]))

(deftype InMemoryPlanState [state]
  impl/StateGet
  (get-state [_ scope-map path default]
    (->> scope-map
         (map #(vector % (get-in state (concat % path) default)))))
  impl/StateUpdate
  (update-state [_ scope-kw scope-val f args]
    (apply update-in state [scope-kw scope-val] f args)))

(defn in-memory-plan-state
  "Return an in-memory plan-state."
  ([initial-state]
     (InMemoryPlanState. initial-state))
  ([]
     (in-memory-plan-state {})))
