(ns pallet.compute.test-provider
  "A provider for testing. Nodes that are created are not real."
  (:require
   [clojure.core.async :refer [>! <! close! go]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.palletops.api-builder.core :refer [assert*]]
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
   [schema.core :as schema :refer [check optional-key validate]]))


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
           {:provider :test-provider
            :operation operation}))

(deftype TestProvider
    [node-list environment tag-provider]
  pallet.core.protocols.Closeable
  (close
    [compute])

  pallet.compute.protocols.ComputeService
  (nodes
    [compute ch]
    "List nodes. A sequence of node instances will be put onto the channel, ch."
    (go-try ch
      (>! ch {:targets @node-list})))

  pallet.compute.protocols/ComputeServiceNodeCreateDestroy
  (images [compute ch]
    (go-try ch
      (>! ch [nil])))

  (create-nodes
    [compute-service node-spec user node-count options ch]
    (go-try ch
      (let [new-nodes (->>
                       (repeatedly node-count #(gensym "id"))
                       (map #(-> (:image node-spec)
                                 (dissoc :image-id)
                                 (assoc :id (name %1)
                                        :packager :apt
                                        :compute-service compute-service))))]
        (swap! node-list (fnil conj []) new-nodes)
        (>! ch {:new-targets new-nodes}))))

  (destroy-nodes
    [compute-service nodes ch]
    (go-try ch
      (let [known-ids (set (map :id @node-list))
            unknown (remove (comp known-ids :id) nodes)
            known (filter (comp known-ids :id) nodes)
            ids (set (map :id nodes))]
        (when (seq unknown)
          (throw
           (ex-info
            (str "Trying to delete unknown nodes" (pr-str unknown))
            {:unknown unknown})))
        (swap! node-list #(remove (comp ids :id) %))
        (>! ch {:old-targets known}))))

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
    {:provider :test-provider
     :nodes @node-list
     :environment environment})
  pallet.compute.protocols.ComputeServiceNodeBaseName
  (matches-base-name? [_ node-name base-name]
    (assert* node-name "Invalid node-name %s" node-name)
    (assert* base-name "Invalid base-name %s" base-name)
    (let [n (.lastIndexOf node-name "-")]
      (if (not (neg? n))
        (= base-name (subs node-name 0 n))))))


;;;; Compute service constructor

(defn implementation
  [{:keys [node-list environment tag-provider node-file
           bootstrapped]
    :or {bootstrapped true}}]
  (let [tag-provider (NodeTagStatic.
                      {"pallet/state"
                       (pr-str {:bootstrapped (boolean bootstrapped)})})
        nodes (atom node-list)
        service (TestProvider. nodes environment tag-provider)]
    (swap! nodes #(map (fn [node] (assoc node :compute-service service)) %))
    service))


(defn-api test-service
  "Create a test-provider compute service, based on a sequence of
  node maps.

  If no `:test-provider` is not passed, this will look for a file
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
           (optional-key :bootstrapped) schema/Any
           (optional-key :tag-provider)
           pallet.compute.protocols.NodeTagWriter}
          :- pallet.compute.protocols.ComputeService ]]}
  [{:keys [node-list node-file environment tag-provider bootstrapped]
    :as options}]
  (implementation options))
