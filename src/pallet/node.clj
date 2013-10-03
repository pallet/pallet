(ns pallet.node
  "API for nodes in pallet"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.typed :refer [ann AnyInteger Map Nilable]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.logging :refer [trace]]
   [pallet.core.protocols :as impl :refer [Node ComputeService]]
   [pallet.core.types :refer [GroupName Hardware Keyword Proxy User]]))

;;; Nodes
(ann ssh-port [Node -> AnyInteger])
(defn ssh-port
  "Extract the port from the node's user Metadata"
  [node]
  (impl/ssh-port node))

(ann primary-ip [Node -> String])
(defn primary-ip
  "Returns the first public IP for the node."
  [node]
  (impl/primary-ip node))

(ann private-ip [Node -> (Nilable String)])
(defn private-ip
  "Returns the first private IP for the node."
  [node]
  (impl/private-ip node))

(ann is-64bit? [Node -> boolean])
(defn is-64bit?
  "64 Bit OS predicate"
  [node]
  (impl/is-64bit? node))

(ann group-name [Node -> GroupName])
(defn group-name
  "Returns the group name for the node."
  [node]
  (impl/group-name node))

(ann hostname [Node -> String])
(defn hostname
  "Return the node's hostname"
  [node]
  (impl/hostname node))

(ann os-family [Node -> Keyword])
(defn os-family
  "Return a node's os-family, or nil if not available."
  [node]
  (impl/os-family node))

(ann os-version [Node -> String])
(defn os-version
  "Return a node's os-version, or nil if not available."
  [node]
  (impl/os-version node))

(ann running? [Node -> boolean])
(defn running?
  "Predicate to test if node is running."
  [node]
  (impl/running? node))

(ann terminated? [Node -> boolean])
(defn terminated?
  "Predicate to test if node is terminated."
  [node]
  (impl/terminated? node))

(ann id [Node -> String])
(defn id
  "Return the node's id."
  [node]
  (impl/id node))

(ann compute-service [Node -> ComputeService])
(defn compute-service
  "Return the service provider the node was provided by."
  [node]
  (impl/compute-service node))

(ann packager [Node -> Keyword])
(defn packager
 "The packager to use on the node"
 [node]
 (impl/packager node))

(ann image-user [Node -> User])
(defn image-user
  "Return the user that is defined by the image."
  [node]
  (impl/image-user node))

(ann hardware [Node -> Hardware])
(defn hardware
  "Return a map with `:cpus`, `:ram`, and `:disks` information. The
ram is reported in Mb. The `:cpus` is a sequence of maps, one for each
cpu, containing the number of `:cores` on each. The `:disks` is a
sequence of maps, containing a :size key for each drive, in Gb. Other
keys may be present."
  [node]
  (impl/hardware node))

(ann proxy [Node -> Proxy])
(defn proxy
  "A map with SSH proxy connection details."
  [node]
  (impl/proxy node))

(ann node? [Any -> boolean])
(defn node?
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (instance? pallet.core.protocols.Node obj))

(ann node-in-group? [GroupName Node -> boolean])
(defn node-in-group? [grp-name node]
  (= (name grp-name) (group-name node)))

(ann node-address [Node -> (Nilable String)])
(defn node-address
  [node]
  {:pre [(node? node)]}
  (cond
    (primary-ip node) (primary-ip node)
    :else (private-ip node)))

(ann tag (Fn [Node String -> String]
             [Node String String -> String]))
(defn tag
  "Return the specified tag."
  ([node tag-name]
     (impl/node-tag (compute-service node) node tag-name))
  ([node tag-name default-value]
     (impl/node-tag (compute-service node) node tag-name default-value)))

(ann tags [Node -> (Map String String)])
(defn tags
  "Return the tags."
  [node]
  (impl/node-tags (compute-service node) node))

(ann tag! [Node String String -> nil])
(defn tag!
  "Set a value on the given tag-name."
  [node tag-name value]
  (impl/tag-node! (compute-service node) node tag-name value))

(ann taggable? [Node -> boolean])
(defn taggable?
  "Predicate to test the availability of tags."
  [node]
  (impl/node-taggable? (compute-service node) node))

(ann node-map [Node -> (U
                        (HMap :mandatory {:proxy Proxy
                                          :ssh-port AnyInteger
                                          :primary-ip String
                                          :private-ip (Nilable String)
                                          :is-64bit? boolean
                                          :group-name String
                                          :hostname String
                                          :os-family Keyword
                                          :os-version String
                                          :running? boolean
                                          :terminated? boolean
                                          :id String})
                        (HMap :mandatory {:primary-ip (Value "N/A")
                                          :host-name (Value "N/A")}))])
(defn node-map
  "Convert a node into a map representing the node."
  [node]
  (try
    {:proxy (proxy node)
     :ssh-port (ssh-port node)
     :primary-ip (primary-ip node)
     :private-ip (private-ip node)
     :is-64bit? (is-64bit? node)
     :group-name (name (group-name node))
     :hostname (hostname node)
     :os-family (os-family node)
     :os-version (os-version node)
     :running? (running? node)
     :terminated? (terminated? node)
     :id (id node)}
    (catch Exception e
      (trace e (with-out-str (print-cause-trace e)))
      {:primary-ip "N/A" :host-name "N/A"})))
