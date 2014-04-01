(ns pallet.settings
  "Settings maps provide an interface to the plan-state based on
  target node ids and a facility keyword."
  (:require
   [pallet.core.plan-state :as plan-state]
   [pallet.node :as node]
   [pallet.session :refer [plan-state target target-session?]]))

;; ;;; ## Settings
(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  ([session facility {:keys [instance-id default] :as options}]
     {:pre [(target-session? session)]}
     (plan-state/get-settings
      (plan-state session)
      (node/id (target session)) facility options))
  ([session facility]
     (get-settings session facility {})))

(defn get-target-settings
  "Retrieve the settings for the `facility` on the `node`. The instance-id
   allows the specification of specific instance of the facility. If passed a
   nil `instance-id`, then `:default` is used"
  ([session target facility {:keys [instance-id default] :as options}]
     (plan-state/get-settings
      (plan-state session) (node/id target) facility options))
  ([session target facility]
     (get-target-settings session target facility {})))

(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  ([session facility kv-pairs {:keys [instance-id] :as options}]
     {:pre [(target-session? session)]}
     (plan-state/assoc-settings
      (plan-state session)
      (node/id (target session))
      facility
      kv-pairs
      options))
  ([session facility kv-pairs]
     (assoc-settings session facility kv-pairs {})))

(defn assoc-in-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  ([session facility path value {:keys [instance-id] :as options}]
     (plan-state/update-settings
      (plan-state session)
      (node/id (target session))
      facility
      assoc-in [path value] options))
  ([session facility path value]
     (assoc-in-settings session facility path value {})))

(defn update-settings
  "Update the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  {:arglists '[[facility f & args][facility options f & args]]}
  [session facility f-or-opts & args]
  (let [[options f args] (if (or (map? f-or-opts) (nil? f-or-opts))
                           [f-or-opts (first args) (rest args)]
                           [nil f-or-opts args])]
    (assert f "nil update function")
    (plan-state/update-settings
     (plan-state session)
     (node/id (target session))
     facility
     f args options)))
