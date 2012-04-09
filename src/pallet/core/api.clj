(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [pallet.node :as node])
  (:use
   [pallet.compute :only [nodes]]))

(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  [group-name]
  (fn has-group-name? [node]
    (= group-name (node/group-name node))))

(defn node-in-group?
  "Check if a node satisfies a group's node-predicate."
  [node group]
  ((:node-predicate group (node-has-group-name? (:group-name node))) node))

(defn node->server
  "Build a server map from a node and a list of groups"
  [groups node]
  (let [groups (filter (partial node-in-group? node) groups)]
    {:node node
     :groups groups}))

(defn query-nodes
  "Query the available nodes"
  [compute groups]
  (map (partial node->server groups) (nodes compute)))
