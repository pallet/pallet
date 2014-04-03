(ns pallet.compute.node-list
  "A simple node list provider.

   The node-list provider enables pallet to work with a server rack or existing
   virtual machines. It works by maintaining a list of nodes. Each node
   minimally provides an IP address, a host name, a group name and an operating
   system. Nodes are constructed using `make-node`.

   An instance of the node-list provider can be built using
   `node-list`.

       (node-list
         {:node-list
          [{:id \"host1\" :public-ip \"192.168.1.101\"
            :os-family :ubuntu :packager :apt}
           {:id \"host2\" :public-ip \"192.168.1.102\"
            :os-family :ubuntu :packager :apt}]})"
  (:require
   [clojure.core.async :refer [>! <! close! go]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [taoensso.timbre :as logging]
   [pallet.compute :as compute]
   [pallet.core.api-builder :refer [defn-api]]
   [pallet.environment]
   [pallet.compute.implementation :as implementation]
   [pallet.compute.jvm :as jvm]
   [pallet.compute.protocols :as impl :refer [node-tag]]
   [pallet.core.protocols :as core-impl]
   [pallet.environment :as environment]
   [pallet.node :as node]
   [pallet.utils :refer [apply-map]]
   [pallet.utils.async :refer [go-try]]
   [schema.core :as schema :refer [check optional-key validate]])
  (:import
   java.net.InetAddress))

;;; Node utilities
(def ^:private ip-resolve-failed-msg
  "Unable to resolve %s, maybe provide an ip for the node using :ip")

(defn ip-for-name
  "Resolve the given hostname to an ip address."
  [n]
  (try
    (let [^InetAddress addr (InetAddress/getByName n)]
      (.getHostAddress addr))
    (catch java.net.UnknownHostException e
      (let [msg (format ip-resolve-failed-msg n)]
        (logging/debugf msg)
        (throw
         (ex-info msg {:type :pallet/unable-to-resolve :host n} e))))))


(deftype NodeTagStatic
    [static-tags]
  pallet.compute.protocols.NodeTagReader
  (node-tag [_ node tag-name]
    (get static-tags tag-name))
  (node-tag [_ node tag-name default-value]
    (or (get static-tags tag-name) default-value))
  (node-tags [_ node]
    static-tags)
  pallet.compute.protocols.NodeTagWriter
  (tag-node! [_ node tag-name value]
    (throw
     (ex-info
      "Attempt to call node-tags on a node that doesn't support mutable tags.
You can pass a :tag-provider to the compute service constructor to enable
support."
      {:reason :unsupported-operation
       :operation :pallet.compute/node-tags})))
  (node-taggable? [_ node] false))

(defn- unsupported-exception [operation]
  (ex-info "Unsupported Operation"
           {:provider :node-list
            :operation operation}))

(deftype NodeList
    [node-list environment tag-provider]
  pallet.core.protocols.Closeable
  (close
    [compute])

  pallet.compute.protocols.ComputeService
  (nodes
    [compute ch]
    "List nodes. A sequence of node instances will be put onto the channel, ch."
    (go-try ch
      (>! ch [@node-list])))

  pallet.environment.protocols.Environment
  (environment [_] environment)
  pallet.compute.protocols.NodeTagReader
  (node-tag [compute node tag-name]
    (impl/node-tag tag-provider node tag-name))
  (node-tag [compute node tag-name default-value]
    (impl/node-tag tag-provider node tag-name default-value))
  (node-tags [compute node]
    (impl/node-tags tag-provider node))
  pallet.compute.protocols.NodeTagWriter
  (tag-node! [compute node tag-name value]
    (impl/tag-node! tag-provider node tag-name value))
  (node-taggable? [compute node]
    (impl/node-taggable? tag-provider node))
  pallet.compute.protocols.ComputeServiceProperties
  (service-properties [_]
    {:provider :node-list
     :nodes @node-list
     :environment environment})
  pallet.compute.protocols.ComputeServiceNodeBaseName
  (matches-base-name? [_ node-name base-name]
    (let [n (.lastIndexOf node-name "-")]
      (if (not (neg? n))
        (= base-name (subs node-name 0 n))))))

(defn node-data->node
  "Convert an external node data specification to a node."
  ([node-data] node-data)
  ([node-data group-name]
     (assoc node-data :hostname group-name)))

(def possible-node-files
  [".pallet-nodes.edn"
   (.getPath (io/file (System/getProperty "user.home") ".pallet" "nodes.edn"))
   "/etc/pallet/nodes.edn"])

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
    (edn/read-string (slurp file))))

(defn- node-file-data->node-list
  [data file]
  (cond
   (map? data)
   (do
     (when-not (every? vector? (vals data))
       (throw
        (ex-info
         (str "Invalid node-file data " (pr-str data)
              " in " (pr-str file)
              ".  Map values for each group should be a vector of nodes.")
         {:type :pallet/invalid-node-file
          :file file
          :node-file-data data})))
     (reduce-kv
      (fn [nodes group group-nodes]
        (concat nodes (map
                       #(node-data->node % (keyword group))
                       group-nodes)))
      []
      data))

   (vector? data) (map node-data->node data)

   :else (throw
          (ex-info
           (str "Invalid node-file data " (pr-str data)
                " in " (pr-str file)
                ".  Expect a map from group-name to vector of nodes.")
           {:type :pallet/invalid-node-file
            :file file
            :node-file-data data}))))

(defn read-node-file
  "Read the contents of node file if it exists."
  [file]
  (when file
    (let [data (read-file file)]
      (node-file-data->node-list data file))))

;;;; Compute Service SPI
(defn supported-providers
  {:no-doc true
   :doc "Returns a sequence of providers that are supported"}
  [] [:node-list])

(defmethod implementation/service :node-list
  [_ {:keys [node-list environment tag-provider node-file]
      :or {tag-provider
           (NodeTagStatic. {"pallet/state" "{:bootstrapped true}"})}}]
  (let [nodes (atom
               ;; An explicit node-list has priority,
               ;; then an explicit node-file,
               ;; then the standard node-file locations
               (or (and node-list (mapv node-data->node (or node-list)))
                   (read-node-file (or node-file (available-node-file)))))
        nodelist (NodeList. nodes environment tag-provider)]
    (swap! nodes #(map (fn [node] (assoc node :compute-service nodelist)) %))
    nodelist))

;;;; Compute service constructor
(defn-api node-list
  "Create a node-list compute service, based on a sequence of
  node maps.

  If no `:node-list` is not passed, this will look for a file
  describing the nodes.  Default locations are ${PALLET_HOSTS},
  ./.pallet-nodes.edn, ~/.pallet/nodes.edn and /etc/pallet/nodes.edn,
  in that order of priority.

  A node descriptor in the nodes config file is a node-map.

  The node file is either a vector of node maps, or a map from
  group name to vector of node maps.

  Optionally, an environment map can be passed using the :environment
  keyword.  See `pallet.environment`."
  {:added "0.9.0"
   :sig [[{(optional-key :node-list) [node/node-schema]
           (optional-key :node-file) String
           (optional-key :environment) {schema/Keyword schema/Any}
           (optional-key :tag-provider)
           pallet.compute.protocols.NodeTagWriter}
          :- pallet.compute.protocols.ComputeService ]]}
  [{:keys [node-list node-file environment tag-provider] :as options}]
  (compute/instantiate-provider :node-list options))
