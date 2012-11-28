(ns pallet.compute.node-list
  "A simple node list provider.

   The node-list provider enables pallet to work with a server rack or existing
   virtual machines. It works by maintaining a list of nodes. Each node
   minimally provides an IP address, a host name, a group name and an operating
   system. Nodes are constructed using `make-node`.

   An instance of the node-list provider can be built using
   `node-list-service`.

       (node-list-service
         [[\"host1\" \"fullstack\" \"192.168.1.101\" :ubuntu]
          [\"host2\" \"fullstack\" \"192.168.1.102\" :ubuntu]])"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.implementation :as implementation]
   [pallet.environment :as environment]
   [pallet.node :as node]
   [clojure.string :as string])
  (:use
   [pallet.utils :only [apply-map]]
   [slingshot.slingshot :only [throw+]]))

(defrecord Node
    [name group-name ip os-family os-version id ssh-port private-ip is-64bit
     running service hardware]
  pallet.node.Node
  (ssh-port [node] ssh-port)
  (primary-ip [node] ip)
  (private-ip [node] private-ip)
  (is-64bit? [node] (:is-64bit node))
  (group-name [node] group-name)
  (running? [node] running)
  (terminated? [node] (not running))
  (os-family [node] os-family)
  (os-version [node] os-version)
  (hostname [node] name)
  (id [node] id)
  (compute-service [node] service)
  pallet.node.NodePackager
  (packager [node] (compute/packager-for-os os-family os-version))
  pallet.node.NodeHardware
  (hardware [node] hardware))

;;; Node utilities
(defn make-node
  "Returns a node, suitable for use in a node-list."
  [name group-name ip os-family
   & {:keys [id ssh-port private-ip is-64bit running os-version service
             hardware]
      :or {ssh-port 22 is-64bit true running true}}]
  (Node.
   name
   group-name
   ip
   os-family
   os-version
   (or id (str name "-" (string/replace ip #"\." "-")))
   ssh-port
   private-ip
   is-64bit
   running
   service
   hardware))

(deftype NodeTagUnsupported
    []
  pallet.compute.NodeTagReader
  (node-tag [_ node tag-name] nil)
  (node-tag [_ node tag-name default-value] default-value)
  (node-tags [_ node] nil)
  pallet.compute.NodeTagWriter
  (tag-node! [_ node tag-name value]
    (throw+
     {:reason :unsupported-operation
      :operation :pallet.compute/node-tags}
     "Attempt to call node-tags on a node that doesn't support mutable tags.
You can pass a :tag-provider to the compute service constructor to enable
support."))
  (node-taggable? [_ node] false))

(deftype NodeList
    [node-list environment tag-provider]
  pallet.compute.ComputeService
  (nodes [compute-service] @node-list)
  (ensure-os-family
    [compute-service group-spec]
    (when (not (-> group-spec :image :os-family))
      (throw+
       {:type :no-os-family-specified
        :message "Node list contains a node without os-family"})))
  ;; Not implemented
  ;; (run-nodes [node-type node-count request init-script options])
  ;; (reboot "Reboot the specified nodes")
  (boot-if-down [compute nodes] nil)
  ;; (shutdown-node "Shutdown a node.")
  ;; (shutdown "Shutdown specified nodes")

  ;; this forgets about the nodes
  (destroy-nodes-in-group [_ group]
    (swap! node-list (fn [nl] (remove #(= (node/group-name %) group) nl))))

  (close [compute])
  pallet.environment.Environment
  (environment [_] environment)
  pallet.compute.NodeTagReader
  (node-tag [compute node tag-name]
    (compute/node-tag tag-provider node tag-name))
  (node-tag [compute node tag-name default-value]
    (compute/node-tag tag-provider node tag-name default-value))
  (node-tags [compute node]
    (compute/node-tags tag-provider node))
  pallet.compute.NodeTagWriter
  (tag-node! [compute node tag-name value]
    (compute/tag-node! tag-provider node tag-name value))
  (node-taggable? [compute node]
    (compute/node-taggable? tag-provider node)))



(defmethod clojure.core/print-method Node
  [^Node node writer]
  (.write
   writer
   (format
    "%14s\t %s %s public: %s  private: %s  %s"
    (:group-name node)
    (:os-family node)
    (:running node)
    (:ip node)
    (:private-ip node)
    (:id node))))

(defn make-localhost-node
  "Make a node representing the local host. This calls `make-node` with values
   inferred for the local host. Takes options as for `make-node`.

       :name \"localhost\"
       :group-name \"local\"
       :ip \"127.0.0.1\"
       :os-family (pallet.compute.jvm/os-family)"
  [& {:keys [name group-name ip os-family id]
      :or {name "localhost"
           group-name "local"
           ip "127.0.0.1"
           os-family (jvm/os-family)}
      :as options}]
  (apply
   make-node name group-name ip os-family
   (apply concat (merge {:id "localhost"} options))))


;;;; Compute Service SPI
(defn supported-providers
  {:no-doc true
   :doc "Returns a sequence of providers that are supported"}
  [] ["node-list"])

(defmethod implementation/service :node-list
  [_ {:keys [node-list environment tag-provider]
      :or {tag-provider (NodeTagUnsupported.)}}]
  (let [nodes (atom (vec
                     (map
                      #(if (vector? %)
                         (apply make-node %)
                         %)
                      node-list)))
        nodelist (NodeList. nodes environment tag-provider)]
    (swap! nodes #(map (fn [node] (assoc node :service nodelist)) %))
    nodelist))

;;;; Compute service constructor
(defn node-list-service
  "Create a node-list compute service, based on a sequence of nodes. Each
   node is passed as either a node object constructed with `make-node`,
   or as a vector of arguments for `make-node`.

   Optionally, an environment map can be passed using the :environment keyword.
   See `pallet.environment`."
  {:added "0.6.8"}
  [node-list & {:keys [environment tag-provider] :as options}]
  (apply-map
   compute/compute-service :node-list (assoc options :node-list node-list)))
