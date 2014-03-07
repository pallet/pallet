(ns pallet.core.plan-state.protocols
  "Protocols for the plan-state service.

A scope is a host, facility, instance-id, provider, group, world, etc.")

;;  is a facility, instance-id a path or a scope
(defprotocol StateGet
  (get-state [_ scope-map path default-value]

    "Get the state at `path` (a vector of keys), resolving for scope,
    a map of scopes from scope keyword to scope value.  Return a
    sequence of scope vector, value vector tuples. Return
    `default-value` as value for all scopes requested that do not
    provide a value for path."))

(defprotocol StateUpdate
  (update-state [_ scope-kw scope-val f args]
    "Update the scope state using the function f, passing the current
    state, and applying args.  Return value is undefined."))

(defn plan-state?
  "Predicate for a plan-state"
  [x]
  (satisfies? StateGet x))
