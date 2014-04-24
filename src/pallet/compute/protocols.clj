(ns pallet.compute.protocols
  "Protocols for the compute service"
  (:refer-clojure :exclude [proxy]))

;;; Async Compute Service
;;; Asynchronous compute service protocols.

(defprotocol ComputeService
  "Basic asynchronous compute service."
  (nodes
   [compute ch]
   "List nodes. A sequence of node instances will be put onto the
   channel, ch."))

(defprotocol ComputeServiceNodeCreateDestroy
  (images
   [compute ch]
   "Writes a sequence of images to the channel, ch.")
  (create-nodes
   [compute node-spec user node-count options ch]
   "Start `node-count` nodes using `node-spec`.  Node instances will
   be put onto the channel, ch.  The channel will be closed when the
   command completes.  Not all the requested nodes will necessarily
   start succesfully.  The options map specifies values that may not
   be supported on all providers, including `:node-name`, for
   specifying the node name.")
  (destroy-nodes
   [compute nodes ch]
   "Remove nodes. Any problems will be written to the channel, ch."))

(defprotocol ComputeServiceNodeStop
  (stop-nodes [compute nodes ch] "Stop nodes.")
  (restart-nodes [compute nodes ch] "Restart stopped or suspended nodes."))

(defprotocol ComputeServiceNodeSuspend
  (suspend-nodes [compute nodes ch] "Suspend nodes node.")
  (resume-nodes [compute nodes ch] "Restart stopped or suspended nodes."))

(defprotocol ComputeServiceTags
  (tag-nodes
   [compute nodes tags ch]
   "Tag the `nodes` in `compute-service` with the `tags`.  Any
   problems will be written to the channel, ch."))

(defprotocol ComputeServiceProperties
  (service-properties [compute]
    "Return a map of service details.  Contains a :provider key at a minimum.
    May contain current credentials."))

(defprotocol ComputeServiceNodeBaseName
  "Nodes names are made unique by the compute service, given a base name."
  (matches-base-name? [compute ^String node-name ^String base-name]
    "Predicate to test if the node-name has the given base-name."))

(defprotocol NodeTagReader
  "Provides a SPI for tagging nodes with values."
  (node-tag [compute node tag-name] [compute node tag-name default-value]
    "Return the specified tag on the node.")
  (node-tags [compute node]
    "Return the tags on the node."))

(defprotocol NodeTagWriter
  "Provides a SPI for adding tags to nodes."
  (tag-node! [compute node tag-name value]
    "Set a value on the given tag-name on the node.")
  (node-taggable? [compute node]
    "Predicate to test the availability of tags on a node."))

(defprotocol NodeBaseName
  "Nodes names are made unique by the compute service, given a base name."
  (has-base-name? [node base-name]
    "Predicate for a node having the specified base name."))

(defprotocol JumpHosts
  "Provide a SPI for specifying jump-hosts"
  (jump-hosts [compute]))
