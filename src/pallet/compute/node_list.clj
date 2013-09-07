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
   [clj-schema.schema
    :refer [def-map-schema optional-path seq-schema sequence-of set-of wild]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.compute :as compute]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.contracts :refer [check-spec]]
   [pallet.environment :as environment]
   [pallet.node :as node]
   [pallet.utils :refer [apply-map]])
  (:import
   java.net.InetAddress))

(defrecord Node
    [name group-name ip os-family os-version id ssh-port private-ip is-64bit
     running service hardware proxy image-user]
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
  (hardware [node] hardware)
  pallet.node.NodeImage
  (image-user [node] image-user)
  pallet.node.NodeProxy
  (proxy [node] proxy))

;;; Node utilities
(def ^:private ip-resolve-failed-msg
  "Unable to resolve %s, maybe provide an ip for the node using :ip")

(defn- ip-for-name
  "Resolve the given hostname to an ip address."
  [n]
  (try
    (let [^InetAddress addr (InetAddress/getByName n)]
      (.getHostAddress addr))
    (catch java.net.UnknownHostException e
      (let [msg (format ip-resolve-failed-msg n)]
        (logging/error msg)
        (throw
         (ex-info msg {:type :pallet/unable-to-resolve :host n} e))))))

(defn make-node
  "Returns a node, suitable for use in a node-list."
  [name group-name ip os-family
   & {:keys [id ssh-port private-ip is-64bit running os-version service
             hardware proxy image-user]
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
   hardware
   proxy
   image-user))

(def-map-schema node-args-schema
  [(optional-path [:ip]) String
   (optional-path [:group-name]) String
   (optional-path [:os-family]) keyword?
   (optional-path [:id]) String
   (optional-path [:ssh-port]) number?
   (optional-path [:private-ip]) String
   (optional-path [:is-64bit]) wild
   (optional-path [:running]) wild
   (optional-path [:proxy]) pallet.node.NodeProxy
   (optional-path [:image-user]) pallet.node.NodeImage])

(defmacro check-node-args-spec
  [m]
  (check-spec m `node-args-schema &form))

(defn node
  "Returns a node, suitable for use in a node-list."
  [name
   & {:keys [ip group-name os-family id ssh-port private-ip is-64bit running
             os-version service hardware proxy image-user]
      :or {ssh-port 22 is-64bit true running true}
      :as args}]
  (check-node-args-spec args)
  (let [ip (or ip (ip-for-name name))]
    (Node.
     name
     (or group-name name)
     ip
     os-family
     os-version
     (or id (str name "-" (string/replace ip #"\." "-")))
     ssh-port
     private-ip
     is-64bit
     running
     service
     hardware
     proxy
     image-user)))

(deftype NodeTagStatic
    [static-tags]
  pallet.compute.NodeTagReader
  (node-tag [_ node tag-name]
    (get static-tags tag-name))
  (node-tag [_ node tag-name default-value]
    (or (get static-tags tag-name) default-value))
  (node-tags [_ node]
    static-tags)
  pallet.compute.NodeTagWriter
  (tag-node! [_ node tag-name value]
    (throw
     (ex-info
      "Attempt to call node-tags on a node that doesn't support mutable tags.
You can pass a :tag-provider to the compute service constructor to enable
support."
      {:reason :unsupported-operation
       :operation :pallet.compute/node-tags})))
  (node-taggable? [_ node] false))

(deftype NodeList
    [node-list environment tag-provider]
  pallet.compute.ComputeService
  (nodes [compute-service] @node-list)
  (ensure-os-family
    [compute-service group-spec]
    (when (not (-> group-spec :image :os-family))
      (throw
       (ex-info
        "Node list contains a node without os-family"
        {:type :no-os-family-specified}))))
  ;; Not implemented
  (run-nodes [compute group-spec node-count user init-script options]
    nil)
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
    (compute/node-taggable? tag-provider node))
  pallet.compute.ComputeServiceProperties
  (service-properties [_]
    {:provider :node-list
     :nodes @node-list
     :environment environment}))



(defmethod clojure.core/print-method Node
  [^Node node ^java.io.Writer writer]
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

(defn node-data->node
  "Convert an external node data specification to a node."
  ([node-data]
     (if (vector? node-data)
       (if (and (second node-data) (string? (second node-data)))
         (apply make-node node-data)    ; backwards compatible
         (apply node node-data))
       (if (string? node-data)
         (node node-data)
         node-data)))
  ([node-data group-name]
     (if (vector? node-data)
       (apply node :group-name group-name node-data)
       (if (string? node-data)
         (node node-data)
         (throw (ex-info
                 (str
                  "Invalid node-list node data " (pr-str node-data)
                  ".  See pallet.compute.node-list/node for valid arguments.")
                 {:type :pallet/invalid-node-list
                  :node-data node-data}))))))

(def possible-node-files
  [".pallet-nodes"
   (.getPath (io/file (System/getProperty "user.home") ".pallet" "nodes"))
   "/etc/pallet/nodes"])

(defn available-node-file
  "Return the first available node-file as specified by PALLET_HOSTS,
  or possible-node-files."
  []
  (or (System/getenv "PALLET_HOSTS")
      (first
       (filter #(.exists (io/file %))
               possible-node-files))))

(defn- read-file
  "Read the contents of node file if it exists."
  [file]
  (if (and file (.exists (io/file file)))
    (read-string (slurp file))))

(defn- node-file-data->node-list
  [data file]
  (cond
   (map? data)
   (do
     (when-not (every? vector? (vals data))
       (throw
        (ex-info
         (str "Invalid node-file data " (pr-str data)
              " in " file
              ".  Map values for each group should be a vector of nodes.")
         {:type :pallet/invalid-node-file
          :file file
          :node-file-data data})))
     (reduce-kv
      (fn [nodes group group-nodes]
        (concat nodes (map
                       #(node-data->node % (name group))
                       group-nodes)))
      []
      data))

   (vector? data) (map node-data->node data)

   :else (throw
          (ex-info
           (str "Invalid node-file data " (pr-str data)
                " in " file
                ".  Expect a map from group-name to vector of nodes.")
           {:type :pallet/invalid-node-file
            :file file
            :node-file-data data}))))

(defn read-node-file
  "Read the contents of node file if it exists."
  [file]
  (let [data (read-file file)]
    (node-file-data->node-list data file)))

;;;; Compute Service SPI
(defn supported-providers
  {:no-doc true
   :doc "Returns a sequence of providers that are supported"}
  [] ["node-list"])

(defmethod implementation/service :node-list
  [_ {:keys [node-list environment tag-provider node-file]
      :or {tag-provider (NodeTagStatic. {:bootstrapped true})}}]
  (let [nodes (atom
               (mapv
                node-data->node
                ;; An explicit node-list has priority,
                ;; then an explicit node-file,
                ;; then the standard node-file locations
                (or node-list
                    (read-node-file
                     (or node-file (available-node-file))))))
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
   compute/instantiate-provider
   :node-list (assoc options :node-list node-list)))

(defn node-list
  "Create a node-list compute service, based on a sequence of nodes. Each
   node is passed as either a node object constructed with `make-node`,
   or as a vector of arguments for `make-node`.

   Optionally, an environment map can be passed using the :environment keyword.
   See `pallet.environment`."
  {:added "0.6.8"}
  [& {:keys [node-list node-file environment tag-provider] :as options}]
  (apply-map compute/instantiate-provider :node-list options))
