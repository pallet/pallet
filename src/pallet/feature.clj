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

(defn multilang-script
  "Feature for multi-langauge script execution."
  [] true)

(defn run-nodes-without-bootstrap
  "Feature for creating nodes without bootstrapping them."
  [] true)

(defn taggable-nodes
  "Feature for tagging nodes."
  [] true)

(defn core-user
  "Feature for pallet.core.user."
  [] true)

(defn node-packager
  "Feature for pallet.node/NodePackager."
  [] true)

(defn node-image
  "Feature for pallet.node/NodeImage."
  [] true)

(defn node-hardware
  "Feature for pallet.node/NodeHardware."
  [] true)
