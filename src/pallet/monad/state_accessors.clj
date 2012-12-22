(ns pallet.monad.state-accessors
  "# State Accessors

 The pallet state, known as the 'session', is a map. These monadic functions are
named after the corresponding core functions, suffixed with '-state'.")

(defn update-in-state
  "Return a state-monad function that replaces the current state by the result
of f applied to the current state and that returns the old state."
  {:pallet/plan-fn true}
  [ks f & args]
  (fn update-in-state [s]
    {:pre [(map? s)]}
    [s (apply update-in s ks f args)]))

(defn assoc-state
  "Return a state-monad function that replaces the current state by the result
of assoc'ing the specified kw-value-pairs onto the current state, and that
returns the old state."
  {:pallet/plan-fn true}
  [& kw-value-pairs]
  (fn assoc-state [s]
    {:pre [(map? s)]}
    [s (apply assoc s kw-value-pairs)]))

(defn assoc-in-state
  "Return a state-monad function that replaces the current state by the result
of assoc'ing the specified value onto the current state at the path specified,
and that returns the old state."
  {:pallet/plan-fn true}
  [path value]
  (fn assoc-in-state [s]
    {:pre [(map? s)]}
    [s (assoc-in s path value)]))

(defn dissoc-state
  "Return a state-monad function that removes the specified keys from the
current state, and returns the old state"
  {:pallet/plan-fn true}
  [& keys]
  (fn dissoc-state [s]
    {:pre [(map? s)]}
    [s (apply dissoc s keys)]))

(defn get-state
  "Return a state-monad function that gets the specified key from the current
state."
  {:pallet/plan-fn true}
  ([k default]
     (fn get-state [s] [(get s k default) s]))
  ([k]
     (get-state k nil)))

(defn get-in-state
  "Return a state-monad function that gets the specified key from the current
state."
  {:pallet/plan-fn true}
  ([ks default]
     (fn get-in-state [s]
       {:pre [(map? s)]}
       [(get-in s ks default) s]))
  ([ks]
     (get-in-state ks nil)))

(defn get-session
  "Return a state-monad function that gets the current session."
  {:pallet/plan-fn true}
  []
  (fn get-session [s] [s s]))
