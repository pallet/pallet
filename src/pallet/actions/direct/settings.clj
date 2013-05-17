(ns pallet.actions.direct.settings
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [assoc-in-settings assoc-settings update-settings]]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.session :as session]))

(implement-action assoc-settings :direct
  {:action-type :fn/clojure :location :origin}
  [session facility kv-pairs & {:keys [instance-id] :as options}]
  [(fn [session]
     [kv-pairs (update-in
                session [:plan-state]
                plan-state/assoc-settings
                (session/target-id session) facility kv-pairs options)])
   session])

(implement-action assoc-in-settings :direct
  {:action-type :fn/clojure :location :origin}
  [session [facility & path] value & {:keys [instance-id] :as options}]
  [(fn [session]
     [value (update-in
             session [:plan-state]
             plan-state/update-settings
             (session/target-id session) facility assoc-in [path value]
             options)])
   session])

(implement-action update-settings :direct
  {:action-type :fn/clojure :location :origin}
  [session facility options & args]
  (clojure.tools.logging/warnf
   "facility %s options %s args %s"
   facility options (vec args))
  (let [[options f args] (if (or (nil? options) (map? options))
                           [options (first args) (rest args)]
                           [{} options args])]
    (assert f "Must supply a function")
    [(fn [session]
       [[f args] (update-in
                  session [:plan-state]
                  plan-state/update-settings
                  (session/target-id session) facility f args options)])
     session]))
