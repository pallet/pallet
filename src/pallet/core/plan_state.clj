(ns pallet.core.plan-state
  "Functions to manipulate the plan-state map. The plan-state represents all the
cumulative settings information on the nodes in an operation.

The identifiers are globally unique, so a key can not exist in more
than one parent scope.  Therefore, given a host, and a group, the plan
state should look for info keyed by the host, then keyed by the group,
and does not need to consider a host, group tuple.

- host
- group


TODO: add a plan-state? predicate
"
  (:require
   [clojure.core.typed
    :refer [ann fn> inst tc-ignore
            Hierarchy Map Nilable NonEmptySeqable NonEmptyVec Option]]
   [pallet.core.plan-state.protocols :as impl]
   [pallet.core.types
    :refer [assert-type-predicate Keyword PlanState ScopeValue ScopeMap
            SettingsOptions]]))

;;; # Known Scopes
(ann scope-hierarchy Hierarchy)
(def scope-hierarchy
  (-> (make-hierarchy)
      (derive :host :group)
      (derive :group :service)
      (derive :service :provider)
      (derive :service :cluster)
      (derive :cluster :world)
      (derive :world :universe)

      (derive :host :role)
      (derive :cluster :role)))

(ann scope-comparator java.util.Comparator)
(def scope-comparator
  (comparator #(isa? scope-hierarchy %1 %2)))

;;; # Type predicate
(ann ^:no-check plan-state? (predicate impl/StateGet))
(defn plan-state?
  "Predicate for checking the type of a plan-state."
  [x]
  (and (satisfies? impl/StateGet x)
       (satisfies? impl/StateUpdate x)))

;;; # Get and Update Scopes
;; TODO - remove no-check when inst can specify type for trans-dot arguments
;; (used in comp)
(ann ^:no-check get-scopes
     (Fn
      [impl/StateGet ScopeMap (NonEmptyVec Keyword) -> Any]
      [impl/StateGet ScopeMap (NonEmptyVec Keyword) Any -> Any]))
(defn get-scopes
  "Given a map of scopes, return a map of scope, value tuples for path.
  Scopes which do not provide a value of path will not present in the
  result map."
  ([plan-state scopes path]
     {:pre [(satisfies? impl/StateGet plan-state)]}
     (->> (impl/get-state plan-state scopes path ::not-found)
          (remove ((inst comp Any Any (NonEmptyVec Any))
                   #{::not-found} second))
          (into {})))
  ([plan-state scopes path default]
     {:pre [(satisfies? impl/StateGet plan-state)]}
     (->> (impl/get-state plan-state scopes path default)
          ((fn [x] (assert-type-predicate x map?)))
          (into {}))))

(ann get-scope
     (Fn
      [impl/StateGet Keyword Any (NonEmptyVec Keyword) -> Any]
      [impl/StateGet Keyword Any (NonEmptyVec Keyword) Any -> Any]))
(defn get-scope
  "Given a single scope, return the value at path.
  Return `default`, or nil if not specified, if the path is not specified
  in scope."
  ([plan-state scope-kw scope-val path default]
     {:pre [(tc-ignore (satisfies? impl/StateGet plan-state))]}
     (-> (impl/get-state plan-state {scope-kw scope-val} path default)
         first second))
  ([plan-state scope-kw scope-val path]
     (get-scope plan-state scope-kw scope-val path nil)))

(ann ^:no-check update-scope
     [impl/StateUpdate Keyword Any (Fn [Any * -> Any])
      (Nilable (NonEmptySeqable Any)) -> Any])
(defn update-scope
  "Update the scope given by scope-kw and scope-val, using the
  function f, passing the current state, and applying args.
  Return value is undefined."
  [plan-state scope-kw scope-val f & args]
  {:pre [(satisfies? impl/StateUpdate plan-state)]}
  (impl/update-state plan-state scope-kw scope-val f args))

;;; # Process Scopes
;; TODO use (HVec Keyword Any) in the result type when it doesn't cause
;; a core.typed error
(ann ^:no-check sort-scopes
     [ScopeMap -> (Nilable (NonEmptySeqable ScopeValue))])
(defn sort-scopes
  [scope-map]
  (->> (keys scope-map)
       (sort scope-comparator)
       (map #(find scope-map %))
       (remove nil?)))

(ann ^:no-check merge-scopes [ScopeMap -> (HMap)])
(defn merge-scopes
  "Given a scope map, merge the values from least specific to most specific"
  [scope-map]
  {:pre [(every? map? (vals scope-map))]}
  (reduce
   (fn> [r :- (HMap) v :- ScopeValue]
     (merge (assert-type-predicate (val v) map?) r))
   {}
   (sort-scopes scope-map)))

(ann ^:no-check merge-scopes-with [(Fn [Any Any -> Any]) ScopeMap -> (HMap)])
(defn merge-scopes-with
  "Given a scope map, merge the values from least specific to most specific"
  [f scope-map]
  {:pre [(fn? f) (every? map? (vals scope-map))]}
  (reduce
   (fn> [r :- (HMap) v :- ScopeValue]
     (merge-with f (assert-type-predicate (val v) map?) r))
   {}
   (sort-scopes scope-map)))

;;; # Settings
(ann ^:no-check get-settings [PlanState String Keyword SettingsOptions -> Any])
(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  [plan-state node-id facility {:keys [instance-id default]}]
  (get-scope plan-state :host node-id
             [::settings facility instance-id] default))

(ann ^:no-check assoc-settings
     (All [ k v]
          [PlanState String Keyword (Map k v) SettingsOptions
           -> PlanState]))
(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [plan-state node-id facility kw-values {:keys [instance-id]}]
  (update-scope plan-state :host node-id
                update-in [::settings facility instance-id]
                merge kw-values))

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
  (apply update-scope plan-state :host node-id
         update-in [::settings facility instance-id]
         f args))
