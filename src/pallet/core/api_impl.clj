(ns pallet.core.api-impl
  "Implementation functions for the core api."
  (:require
   [pallet.compute :as compute]
   [pallet.node :as node])
  (:use
   [pallet.map-merge :only [merge-key merge-keys]]
   [pallet.script :only [with-script-context]]
   [pallet.stevedore :only [with-script-language]]))

(defn pipeline
  [a b]
  (fn [& args] (apply a args) (apply b args)))

(defmethod merge-key :merge-phases
  [_ _ val-in-result val-in-latter]
  (merge-with pipeline val-in-result val-in-latter))

(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-phases
   :roles :union
   :group-names :union})

(defn merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  {:internal true}
  [group-name]
  (fn has-group-name? [node]
    (when-let [node-group (node/group-name node)]
      (= group-name (name node-group)))))

(defn node-in-group?
  "Check if a node satisfies a group's node-predicate."
  {:internal true}
  [node group]
  ((:node-predicate group (node-has-group-name? (name (:group-name group))))
   node))

(defn node->node-map
  "Build a map entry from a node and a list of groups"
  {:internal true}
  [groups]
  (fn [node]
    (when-let [groups (seq (->>
                            groups
                            (filter (partial node-in-group? node))
                            (map #(assoc-in % [:group-names]
                                            (set [(:group-name %)])))))]
      (reduce
       (partial merge-specs merge-spec-algorithm)
       {:node node}
       groups))))

(defn script-template-for-node
  [target]
  {:pre [target (:node target)]}
  (let [node (:node target)
        family (node/os-family node)]
    (filter identity
            [family
             (or (:packager target) (node/packager node))
             (when-let [version (node/os-version node)]
               (keyword (format "%s-%s" (name family) version)))])))

(defmacro with-script-for-node
  "Set up the script context for a server"
  [target & body]
  `(with-script-context (script-template-for-node ~target)
     (with-script-language :pallet.stevedore.bash/bash
       ~@body)))
