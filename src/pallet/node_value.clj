(ns pallet.node-value
  "A node value is a value that is retrieved from a node and can only be deref'd
   when after the value as been provided by an action.

   The action may not be executed on the same pallet machine as the phase, so
   the actual value is in the session map, and the node-value contains a key
   into the session map's :node-values key."
  (:use
   [pallet.common.context :only [throw-map]]
   [pallet.argument :only [DelayedArgument *session*]]))

;; (defprotocol SetableNodeValue
;;   "A protocol used to set node-values"
;;   (node-value! [_ value]))

(defprotocol NodeValueAccessor
  "A protocol used to read node-values"
  (node-value [_ session]))

(defn invalid-access
  "Throw an exception on access other than deref"
  [s]
  (throw-map
   "Invalid access of a node-value that has yet to be set by an action."
   {:type :pallet/access-of-node-value-without-deref
    :via s}))

;; A value that is set based on some value from a node.
;; We implement several interfaces, just to ensure that they do not silently
;; fail.

;; Use IFn to read value from session?
(deftype NodeValue
    [path]
  ;; SetableNodeValue
  ;; (node-value! [_ new-value] (reset! value new-value))
  NodeValueAccessor
  (node-value [_ session]
    (let [rv (get-in session [:plan-state :node-values path] ::not-set)]
      (if (= rv ::not-set)
        (throw-map
         (str
          "Invalid access of a node-value that has yet to be set by an action. "
          "If you are using an expression involving a node-value as an "
          "argument to a plan function, you should wrap the expression in a "
          "`delayed` form.\n\n"
          "    (pallet.argument/delayed [session] @node-value)")
         {:type :pallet/access-of-unset-node-value
          :path path})
        rv)))
  DelayedArgument
  (evaluate [x session]
    (node-value x session))
  Object
  (toString [_] (pr-str path))
  clojure.lang.IDeref
  (deref [nv]
    (node-value nv *session*))
  clojure.lang.Associative
  (containsKey [_ key] (invalid-access 'containsKey))
  (entryAt [_ key] (invalid-access 'entryAt))
  (assoc [_ key val] (invalid-access 'assoc)))

(defn make-node-value
  "Create an empty node-value"
  [path]
  (NodeValue. path))

(defn node-value?
  [v]
  (instance? NodeValue v))

(defn set-node-value
  ([session v node-value-path]
     (assoc-in session [:plan-state :node-values node-value-path] v))
  ([session v]
     (set-node-value session v (:current-node-value-path session))))

(defn assign-node-value
  [nv v]
  (fn [session]
    [v (assoc-in session [:plan-state :node-values (.path nv)] v)]))

(defn get-node-value
  [nv]
  (fn [session] [(node-value nv session) session]))
