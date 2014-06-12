(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [clojure.core.async :as async :refer [<!! <! >! chan]]
   [clojure.set :refer [intersection]]
   [pallet.compute.protocols :as impl]
   [pallet.compute.implementation :as implementation]
   [pallet.core.protocols :as core-impl]
   [pallet.kb :refer [packager-for-os]]
   [pallet.utils.async :refer [go-try]]
   [schema.core :as schema :refer [check maybe optional-key validate]]))

;;; ## Schema types

;;; node-spec contains loose schema, as these vary by, and should be enforced by
;;; the providers.
(def ImageSpec
  {:image-id (schema/either String schema/Keyword)
   :os-family schema/Keyword
   (optional-key :packager) schema/Keyword
   schema/Keyword schema/Any})

(def ImageSearchSchema
  {(optional-key :image-id) (schema/either String schema/Keyword)
   (optional-key :image-description-matches) String
   (optional-key :image-name-matches) String
   (optional-key :image-version-matches) String
   (optional-key :os-family) schema/Keyword
   (optional-key :os-64-bit) schema/Bool
   (optional-key :os-arch-matches) String
   (optional-key :os-description-matches) String
   (optional-key :os-name-matches) String
   (optional-key :os-version-matches) String
   (optional-key :hypervisor-matches) String
   (optional-key :override-login-user) String
   schema/Keyword schema/Any})

(def LocationSpec
  {(optional-key :location-id) String
   schema/Keyword schema/Any})

(def HardwareSpec
  {(optional-key :hardware-id) String
   (optional-key :min-ram) Number
   (optional-key :min-cores) Number
   (optional-key :min-disk) Number
   schema/Keyword schema/Any})

(def InboundPortSpec
  {:start-port Number
   (optional-key :end-port) Number
   (optional-key :protocol) String})

(def InboundPort
  (schema/either InboundPortSpec Number))

(def NetworkSpec
  {(optional-key :inbound-ports) [InboundPort]
   schema/Keyword schema/Any})

(def QosSpec
  {(optional-key :spot-price) Number
   (optional-key :enable-monitoring) schema/Bool
   schema/Keyword schema/Any})

(def NodeSpec
  (schema/named
   {(optional-key :image) ImageSpec
    (optional-key :location) LocationSpec
    (optional-key :hardware) HardwareSpec
    (optional-key :network) NetworkSpec
    (optional-key :qos) QosSpec
    (optional-key :provider) {schema/Keyword schema/Any}
    schema/Keyword schema/Any}
   'NodeSpec))

(def NodeSpecMeta
  {:node-spec NodeSpec
   (optional-key :selectors) #{schema/Keyword}
   (optional-key :group-suffix) String
   (optional-key :name) String})

;;; Meta
(defn supported-providers
  "Return a list of supported provider names.
Each name is suitable to be passed to compute-service."
  []
  (implementation/supported-providers))

;;; Compute Service instantiation
(def ^:private
  missing-provider-re
  #"No method in multimethod 'service' for dispatch value: (.*)")

(defn instantiate-provider
  "Instantiate a compute service. The provider name should be a recognised
jclouds provider, \"node-list\", \"hybrid\", or \"localhost\". The other
arguments are keyword value pairs.

   - :identity     username or key
   - :credential   password or secret
   - :extensions   extension modules for jclouds
   - :node-list    a list of nodes for the \"node-list\" provider.
   - :environment  an environment map with service specific values.

Provider specific options may also be passed."
  [provider-name
   {:keys [identity credential extensions node-list endpoint environment
           sub-services]
    :as options}]
  (implementation/load-providers)
  (try
    (implementation/service provider-name options)
    (catch IllegalArgumentException e
      (if-let [[_ provider] (if-let [msg (.getMessage e)]
                              (re-find missing-provider-re msg))]
        (let [cause (cond
                     (= provider :vmfest)
                     "Possible missing dependency on pallet-vmfest."
                     (find-ns 'pallet.compute.jclouds)
                     "Possible missing dependency on a jclouds provider."
                     :else
                     "Possible missing dependency.")]
          (throw (ex-info
                  (str "No pallet provider found for " provider
                       ".  " cause)
                  {:provider provider
                   :cause cause})))
        (throw e)))))

(defn compute-service?
  "Predicate for the argument satisfying the ComputeService protocol."
  [c]
  (satisfies? pallet.compute.protocols/ComputeService c))

;;; Actions
(defn- unsupported-exception [service operation]
  (ex-info "Unsupported Operation"
           {:service service
            :operation operation}))

(defn- require-protocol [protocol service operation]
  (when-not (satisfies? protocol service)
    (throw (unsupported-exception service operation))))

(defn nodes
  "Return the nodes in the compute service.  Returns a rex-map with
  a :targets key."
  [compute ch]
  (require-protocol impl/ComputeService compute 'nodes)
  (impl/nodes compute ch))

(defn create-nodes
  "Create nodes running in the compute service."
  [compute {:keys [image] :as node-spec} user node-count options ch]
  {:pre [(map? node-spec)
         (map? (:image node-spec))
         (validate NodeSpec node-spec)
         (validate (maybe {schema/Keyword schema/Any}) options)]}
  (require-protocol impl/ComputeServiceNodeCreateDestroy compute 'create-nodes)
  (let [node-spec (cond-> node-spec
                          (not (:packager image))
                          (assoc-in [:image :packager]
                            (packager-for-os
                             (:os-family image) (:os-version image))))]
    (impl/create-nodes
     compute node-spec user node-count options ch)))

(defn destroy-nodes
  "Destroy the nodes running in the compute service. Writes arex-tuple
  with a sequence of result maps, with the :return-value set
  to :pallet.compute/target-remved if the node as been successfully
  destroyed."
  [compute nodes ch]
  (require-protocol impl/ComputeServiceNodeCreateDestroy compute 'destroy-nodes)
  (impl/destroy-nodes compute nodes ch))

(defn images
  "Return the images available in the compute service."
  [compute ch]
  (require-protocol impl/ComputeServiceNodeCreateDestroy compute 'images)
  (impl/images compute ch))

(defn restart-nodes
  "Start the nodes running in the compute service."
  [compute nodes ch]
  (require-protocol impl/ComputeServiceNodeStop compute 'restart-nodes)
  (impl/restart-nodes compute nodes ch))

(defn stop-nodes
  "Stop the nodes running in the compute service."
  [compute nodes ch]
  (require-protocol impl/ComputeServiceNodeStop compute 'stop-nodes)
  (impl/stop-nodes compute nodes ch))

(defn suspend-nodes
  "Suspend the nodes running in the compute service."
  [compute nodes ch]
  (require-protocol impl/ComputeServiceNodeSuspend compute 'suspend-nodes)
  (impl/suspend-nodes compute nodes ch))

(defn resume-nodes
  "Resume the nodes running in the compute service."
  [compute nodes ch]
  (require-protocol impl/ComputeServiceNodeSuspend compute 'resume-nodes)
  (impl/resume-nodes compute nodes ch))

(defn tag-nodes
  "Set the `tags` on all `nodes`."
  ([compute nodes tags]
     (let [ch (chan)]
       (impl/tag-nodes compute nodes tags ch)
       (<!! ch)))
  ([compute nodes tags ch]
     (impl/tag-nodes compute nodes tags ch)))

(defn matches-base-name?
  "Resume the nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeBaseName}
  [compute node-name base-name]
  (impl/matches-base-name? compute node-name base-name))

(defn close
  "Close the compute service, releasing any acquired resources."
  [compute]
  (require-protocol core-impl/Closeable compute :close)
  (core-impl/close compute))

(defn service-properties
  "Return a map of service details.  Contains a :provider key at a minimum.
  May contain current credentials."
  [compute]
  (impl/service-properties compute))

(defn jump-hosts
  "Return a sequence of jump hosts for accessing nodes in a compute
  service."
  [compute]
  (if (satisfies? impl/JumpHosts compute)
    (impl/jump-hosts compute)))


;;; # Node spec
(def ^{:doc "Vector of keywords recognised by node-spec"
       :private true}
  node-spec-keys [:image :hardware :location :network])

(defn node-spec
  "Create a node-spec.

   Defines the compute image and hardware selector template.

   This is used to filter a cloud provider's image and hardware list to select
   an image and hardware for nodes created for this node-spec.

   :image     a map describing a predicate for matching an image:
              os-family os-name-matches os-version-matches
              os-description-matches os-64-bit
              image-version-matches image-name-matches
              image-description-matches image-id

   :location  a map describing a predicate for matching location:
              location-id
   :hardware  a map describing a predicate for matching hardware:
              min-cores min-ram smallest fastest biggest architecture
              hardware-id
   :network   a map for network connectivity options:
              inbound-ports
   :qos       a map for quality of service options:
              spot-price enable-monitoring"
  [{:keys [image hardware location network qos] :as options}]
  {:pre [(or (nil? image) (map? image))]
   :post [(validate NodeSpec %)]}
  (vary-meta (or options {}) assoc :type ::node-spec))

;;; # Node Spec Meta Maps

;;; Provides a facility to filter node-spec-meta maps.

;;; A node-spec-meta is a map with a :node-spec key, containing a
;;; node-spec.  It can also have a :selectors key with a set of
;;; keywords, a group-suffix key with a suffix string for node names,
;;; and a :name key with a string value.
(defn matches-selectors?
  "Predicate for matching any of a set of keyword selectors with a
  node-spec meta map."
  [selectors node-spec-meta]
  {:pre [(set? selectors) (validate NodeSpecMeta node-spec-meta)]}
  (seq (intersection selectors (:selectors node-spec-meta))))
