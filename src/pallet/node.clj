(ns pallet.node
  "API for nodes in pallet"
  (:refer-clojure :exclude [:proxy])
  (:use
   [pallet.compute :only [node-tag node-tags tag-node! node-taggable?]]))

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
  (running? [node] "Predicate to test if node is running.")
  (terminated? [node] "Predicate to test if node is terminated.")
  (id [node])
  (compute-service [node]
    "Return the service provider the node was provided by."))

(defprotocol NodePackager
  (packager [node] "The packager to use on the node"))

(defprotocol NodeImage
  (image-user [node] "Return the user that is defined by the image."))

(defprotocol NodeHardware
  (hardware [node]
    "Return a map with `:cpus`, `:ram`, and `:disks` information. The ram is
     reported in Mb. The `:cpus` is a sequence of maps, one for each cpu,
     containing the number of `:cores` on each. The `:disks` is a sequence
     of maps, containing a :size key for each drive, in Gb. Other keys
     may be present."))

(defprotocol NodeProxy
  (proxy [node] "A map with SSH proxy connection details."))

(defn node?
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (instance? pallet.node.Node obj))

(defn node-in-group? [grp-name node]
  (= (name grp-name) (group-name node)))

(defn node-address
  [node]
  (cond
    (string? node) node
    (primary-ip node) (primary-ip node)
    :else (private-ip node)))

(defn tag
  "Return the specified tag."
  ([node tag-name]
     (node-tag (compute-service node) node tag-name))
  ([node tag-name default-value]
     (node-tag (compute-service node) node tag-name default-value)))

(defn tags
  "Return the tags."
  [node]
  (node-tags (compute-service node) node))

(defn tag!
  "Set a value on the given tag-name."
  [node tag-name value]
  (tag-node! (compute-service node) node tag-name value))

(defn taggable?
  "Predicate to test the availability of tags."
  [node]
  (node-taggable? (compute-service node) node))

(defn node-map
  "Convert a node into a map representing the node."
  [node]
  {:primary-ip (primary-ip node)
   :private-ip (private-ip node)
   :ssh-port (ssh-port node)
   :hostname (hostname node)
   :group-name (group-name node)
   :os-family (os-family node)
   :os-version (os-version node)
   :is-64bit? (is-64bit? node)
   :terminated? (terminated? node)
   :running? (running? node)})
