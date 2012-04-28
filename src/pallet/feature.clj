(ns pallet.feature
  "Pallet feature recognition.

   A feature is implemented as a function within the pallet.feature namespace")

(defmacro has-feature?
  "Predicate to test for feature availability"
  [feature]
  (when-let [f (ns-resolve 'pallet.feature feature)]
    (f)))

(defmacro when-feature
  "Predicate to test for feature availability"
  [feature & body]
  (if (has-feature? feature)
    ~@body
    ~@[]))

(defn multilang-script
  "Feature for multi-langauge script execution."
  [] true)

(defn run-nodes-without-bootstrap
  "Feature for creating nodes without bootstrapping them."
  [] true)
