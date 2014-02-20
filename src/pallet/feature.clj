(ns pallet.feature
  "Pallet feature recognition.

   A feature is implemented as a function within the pallet.feature namespace")

(defn has-feature?*
  [feature]
  (when-let [f (ns-resolve 'pallet.feature feature)]
    (f)))

(defmacro has-feature?
  "Predicate to test for feature availability"
  [feature]
  (has-feature?* feature))

(defmacro when-feature
  "Predicate to test for feature availability"
  [feature & body]
  (when (has-feature?* feature)
    `(do ~@body)))

(defmacro when-not-feature
  "Predicate to test for feature availability"
  [feature & body]
  (when-not (has-feature?* feature)
    `(do ~@body)))

(defmacro if-feature
  "Predicate to test for feature availability"
  {:indent 1}
  [feature true-expr false-expr]
  (if (has-feature?* feature)
    true-expr
    false-expr))
