(ns pallet.core.nodes
 "Functions for returning and filtering nodes"
 (:require
  [pallet.compute.node-list :as node-list]))

(defn localhost
  "Returns a node for localhost.  Optionally takes a map as per
`pallet.compute.node-list/make-node`."
  ([options]
     (node-list/make-localhost-node options))
  ([]
     (localhost {})))
