(ns pallet.core.plan-state
  "Functions to manipulate the plan-state map. The plan-state represents all the
  cumulative settings information on the nodes in an operation.")

(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  [plan-state node-id facility
   {:keys [instance-id default] :or {instance-id :default} :as options}]
  (get-in plan-state [:host node-id facility instance-id] default))

(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [plan-state node-id facility values
   {:keys [instance-id] :or {instance-id :default}}]
  (assoc-in plan-state [:host node-id facility instance-id] values))

(defn update-settings
  "Update the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [plan-state node-id facility f args
   {:keys [instance-id] :or {instance-id :default}}]
  (apply update-in plan-state [:host node-id facility instance-id] f args))
