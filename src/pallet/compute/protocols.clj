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
  (group-name [node] "Returns the group name for the node.")
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

;;; # Compute Service
(defprotocol> ComputeService
  (nodes [compute] "List nodes")
  (run-nodes
    [compute group-spec node-count user init-script options]
    "Start node-count nodes for group-spec, executing an init-script
     on each, using the specified user and options.")
  (reboot [compute nodes]
    "Reboot the specified nodes")
  (boot-if-down
   [compute nodes]
   "Boot the specified nodes, if they are not running.")
  (shutdown-node [compute node user] "Shutdown a node.")
  (shutdown [compute nodes user] "Shutdown specified nodes")
  (ensure-os-family
   [compute group-spec]
   "Called on startup of a new node to ensure group-spec has an os-family
   attached to it.")
  (destroy-nodes-in-group [compute group-name])
  (destroy-node [compute node])
  (images [compute])
  (close [compute]))

(defprotocol> ComputeServiceProperties
  (service-properties [compute]
    "Return a map of service details.  Contains a :provider key at a minimum.
    May contain current credentials."))

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
