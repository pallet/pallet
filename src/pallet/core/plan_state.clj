(ns pallet.core.plan-state
  "Functions to manipulate the plan-state map. The plan-state represents all the
  cumulative settings information on the nodes in an operation."
  (:require
   [clojure.core.typed :refer [ann Map Nilable NonEmptySeqable]]
   [pallet.core.types
    :refer [assert-type-predicate Keyword PlanState SettingsOptions]]))

(ann get-settings [PlanState String Keyword SettingsOptions -> Any])
(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  [plan-state node-id facility {:keys [instance-id default]}]
  (get-in plan-state [:host node-id facility instance-id] default))

;; TODO - update once update-in has been special cased in core.typed
(ann ^:no-check assoc-settings
     (All [ k v]
          [PlanState String Keyword (Map k v) SettingsOptions
           -> PlanState]))
(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [plan-state node-id facility kw-values {:keys [instance-id]}]
  (update-in plan-state [:host node-id facility instance-id] merge kw-values))

;; TODO - update once apply, update-in have been special cased in core.typed
(ann ^:no-check update-settings
     (All [ k v]
          [PlanState String Keyword [(Map k v) Any * -> (Map k v)]
           (Nilable (NonEmptySeqable Any)) SettingsOptions
           -> PlanState]))
(defn update-settings
  "Update the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [plan-state node-id facility f args {:keys [instance-id]}]
  {:pre [f]}
  (apply update-in plan-state [:host node-id facility instance-id] f args))
