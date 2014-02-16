(ns pallet.compute.protocols
  "Protocols for the compute service"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.typed :refer [defprotocol>]]))

;;; # Node
(defprotocol> Node
  (ssh-port [node] "Extract the port from the node's userMetadata")
  (primary-ip [node] "Returns the first public IP for the node.")
  (private-ip [node] "Returns the first private IP for the node.")
  (is-64bit? [node] "64 Bit OS predicate")
  (hostname [node] "TODO make this work on ec2")
  (os-family [node] "Return a node's os-family, or nil if not available.")
  (os-version [node] "Return a node's os-version, or nil if not available.")
  (running? [node] "Predicate to test if node is running.")
  (terminated? [node] "Predicate to test if node is terminated.")
  (id [node])
  (compute-service [node]
    "Return the service provider the node was provided by."))

(defprotocol> NodePackager
  (packager [node] "The packager to use on the node"))

(defprotocol> NodeImage
  (image-user [node] "Return the user that is defined by the image."))

(defprotocol> NodeHardware
  (hardware [node]
    "Return a map with `:cpus`, `:ram`, and `:disks` information. The ram is
     reported in Mb. The `:cpus` is a sequence of maps, one for each cpu,
     containing the number of `:cores` on each. The `:disks` is a sequence
     of maps, containing a :size key for each drive, in Gb. Other keys
     may be present."))

(defprotocol> NodeProxy
  (proxy [node] "A map with SSH proxy connection details."))


;;; Async Compute Service
;;; Asynchronous compute service protocols.

(defprotocol> ComputeService
  "Basic asynchronous compute service."
  (nodes
   [compute ch]
   "List nodes. A sequence of node instances will be put onto the
   channel, ch."))

(defprotocol> ComputeServiceNodeCreateDestroy
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

(defprotocol> ComputeServiceNodeStop
  (stop-nodes [compute nodes ch] "Stop nodes.")
  (restart-nodes [compute nodes ch] "Restart stopped or suspended nodes."))

(defprotocol> ComputeServiceNodeSuspend
  (suspend-nodes [compute nodes ch] "Suspend nodes node.")
  (resume-nodes [compute nodes ch] "Restart stopped or suspended nodes."))

(defprotocol> ComputeServiceTags
  (tag-nodes
   [compute nodes tags ch]
   "Tag the `nodes` in `compute-service` with the `tags`.  Any
   problems will be written to the channel, ch."))

;;; # Compute Service
;;; Synchronous compute service protocols.
;; (defprotocol> ComputeService
;;   (nodes [compute] "List nodes")
;;   (run-nodes
;;    [compute node-spec user node-count]
;;    "Start `node-count` nodes using `node-spec`, authorising the public
;;    key of the specified `user` if possible.")
;;   (tag-nodes
;;    [compute nodes tags]
;;    "Tag the `nodes` in `compute-service` with the `tags`.")
;;   (reboot [compute nodes] "Reboot the specified nodes")
;;   (boot-if-down
;;    [compute nodes]
;;    "Boot the specified nodes, if they are not running.")
;;   (shutdown-node [compute node user] "Shutdown a node.")
;;   (shutdown [compute nodes user] "Shutdown specified nodes")
;;   (ensure-os-family
;;    [compute group-spec]
;;    "Called on startup of a new node to ensure group-spec has an os-family
;;    attached to it.")
;;   (destroy-nodes [compute nodes])
;;   (destroy-node [compute node])
;;   (images [compute])
;;   (close [compute]))

(defprotocol> ComputeServiceProperties
  (service-properties [compute]
    "Return a map of service details.  Contains a :provider key at a minimum.
    May contain current credentials."))

(defprotocol> ComputeServiceNodeBaseName
  "Nodes names are made unique by the compute service, given a base name."
  (matches-base-name? [compute node-name base-name]
    "Predicate to test if the node-name has the given base-name."))

(defprotocol> NodeTagReader
  "Provides a SPI for tagging nodes with values."
  (node-tag [compute node tag-name] [compute node tag-name default-value]
    "Return the specified tag on the node.")
  (node-tags [compute node]
    "Return the tags on the node."))

(defprotocol> NodeTagWriter
  "Provides a SPI for adding tags to nodes."
  (tag-node! [compute node tag-name value]
    "Set a value on the given tag-name on the node.")
  (node-taggable? [compute node]
    "Predicate to test the availability of tags on a node."))

(defprotocol> NodeBaseName
  "Nodes names are made unique by the compute service, given a base name."
  (has-base-name? [node base-name]
    "Predicate for a node having the specified base name."))
