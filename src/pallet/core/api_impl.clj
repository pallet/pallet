(ns pallet.core.api-impl
  "Implementation functions for the core api"
  (:require
   [pallet.compute :as compute]
   [pallet.node :as node])
  (:use
   [pallet.map-merge :only [merge-keys]]
   [pallet.script :only [with-script-context]]
   [pallet.stevedore :only [with-script-language]]))

(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-comp
   :roles :union
   :group-name :union})

(defn merge-specs
  "Merge specs, using comp for :phases"
  [algorithms a b]
  (merge-keys algorithms a b))

(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  {:internal true}
  [group-name]
  (fn has-group-name? [node]
    (= group-name (node/group-name node))))

(defn node-in-group?
  "Check if a node satisfies a group's node-predicate."
  {:internal true}
  [node group]
  ((:node-predicate group (node-has-group-name? (name (node/group-name node))))
   node))

(defn node->node-map
  "Build a map entry from a node and a list of groups"
  {:internal true}
  [groups]
  (fn [node]
    (when-let [groups (seq (filter (partial node-in-group? node) groups))]
      (reduce
       (partial merge-specs merge-spec-algorithm)
       {:node node}
       groups))))

(defn script-template-for-node
  [node]
  {:pre [node]}
  (let [family (node/os-family node)]
    (filter identity
            [family
             (node/packager node)
             (when-let [version (node/os-version node)]
               (keyword (format "%s-%s" (name family) version)))])))

(defmacro with-script-for-node
  "Set up the script context for a server"
  [node & body]
  `(with-script-context (script-template-for-node ~node)
     (with-script-language :pallet.stevedore.bash/bash
       ~@body)))
