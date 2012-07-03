(ns pallet.action-impl
  "Provides a data structure for pallet's actions. This is the internal
  representation of an action. User code should use pallet.action, where actions
  are represented via the functions that insert them into the session action
  plan.

  Each action has a symbol associated with an action is used in error messages.
  The symbol does not have to resolve to a var.

  The `execution` specifies the execution model for the action.

  Precedence is stored as a map, with :always-before and :always-after keys.

  Each implementation is represented as a map of metadata and function. The map
  is stored in a map in an atom on the ::impls key of the metadata of the
  action, keyed by the dispatch value. This allows for the implementation
  function to be an anonymous named function, while still having metadata
  associated with it.")


(defn make-action
  "Function to create an action. The action will have no initial
  implementations."
  [action-symbol execution precedence]
  {:action-symbol action-symbol
   :impls (atom {})
   :execution execution
   :precedence precedence})

(defn action-symbol
  "Return the action's symbol."
  [action]
  (:action-symbol action))

(defn action-execution
  "Return the execution model for the action."
  [action]
  (:execution action))

(defn action-precedence
  "Return the precedence map for the action."
  [action]
  (:precedence action))

(defn action-implementation
  ([action dispatch-val default]
     (get @(:impls action) dispatch-val (get @(:impls action) default)))
  ([action dispatch-val]
     (get @(:impls action) dispatch-val)))

(defn add-action-implementation!
  [action dispatch-val metadata f]
  (swap! (:impls action) assoc dispatch-val {:f f :metadata metadata}))
