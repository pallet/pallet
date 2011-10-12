(ns pallet.node)

;;; Nodes
(defprotocol Node
  (ssh-port [node] "Extract the port from the node's userMetadata")
  (primary-ip [node] "Returns the first public IP for the node.")
  (private-ip [node] "Returns the first private IP for the node.")
  (is-64bit? [node] "64 Bit OS predicate")
  (group-name [node] "Returns the group name for the node.")
  (hostname [node] "TODO make this work on ec2")
  (os-family [node] "Return a node's os-family, or nil if not available.")
  (os-version [node] "Return a node's os-version, or nil if not available.")
  (running? [node])
  (terminated? [node])
  (id [node])
  (compute-service [node]
    "Return the service provider the node was provided by."))

(defn node?
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (instance? pallet.node.Node obj))

(defn tag [node] (group-name node))
(defn node-in-group? [group-name node]
  (= (clojure.core/name group-name) (pallet.node/group-name node)))

(defn node-address
  [node]
  (if (string? node)
    node
    (primary-ip node)))
