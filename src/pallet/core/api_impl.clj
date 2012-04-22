(ns pallet.core.api-impl
  "Implementation functions for the core api"
  (:require
   [pallet.compute :as compute]
   [pallet.node :as node])
  (:use
   [pallet.script :only [with-script-context]]
   [pallet.stevedore :only [with-script-language]]))

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
  ((:node-predicate group (node-has-group-name? (:group-name node))) node))

(defn node->server
  "Build a server map from a node and a list of groups"
  {:internal true}
  [groups node]
  (let [groups (filter (partial node-in-group? node) groups)]
    {:node node
     :groups groups}))


(defn script-template-for-node
  [node]
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
