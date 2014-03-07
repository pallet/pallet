(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [clojure.core.async :as async :refer [<!! <! >! chan]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.compute.protocols :as impl]
   [pallet.compute.implementation :as implementation]
   [pallet.core.protocols :as core-impl]
   [pallet.core.version-dispatch :refer [version-map]]
   [pallet.utils :refer [maybe-assoc]]
   [pallet.utils.async :refer [deref-rex go-try]]
   [pallet.versions :refer [as-version-vector]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

;;; ## Schema types

;;; node-spec contains loose schema, as these vary by, and should be enforced by
;;; the providers.
(def image-spec-schema
  {:image-id (schema/either String schema/Keyword)})

(def image-search-schema
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

(def location-spec-schema
  {(optional-key :location-id) String
   schema/Keyword schema/Any})

(def hardware-spec-schema
  {(optional-key :hardware-id) String
   (optional-key :min-ram) Number
   (optional-key :min-cores) Number
   (optional-key :min-disk) Number
   schema/Keyword schema/Any})

(def inbound-port-spec-schema
  {:start-port Number
   (optional-key :end-port) Number
   (optional-key :protocol) String})

(def inbound-port-schema
  (schema/either inbound-port-spec-schema Number))

(def network-spec-schema
  {(optional-key :inbound-ports) [inbound-port-schema]
   schema/Keyword schema/Any})

(def qos-spec-schema
  {(optional-key :spot-price) Number
   (optional-key :enable-monitoring) schema/Bool
   schema/Keyword schema/Any})

(def node-spec-schema
  {(optional-key :image) image-spec-schema
   (optional-key :location) location-spec-schema
   (optional-key :hardware) hardware-spec-schema
   (optional-key :network) network-spec-schema
   (optional-key :qos) qos-spec-schema
   (optional-key :provider) {schema/Keyword schema/Any}
   schema/Keyword schema/Any})

(defn check-node-spec
  [m]
  (validate node-spec-schema m))

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
   & {:keys [identity credential extensions node-list endpoint environment
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
  "Return the nodes in the compute service."
  [compute ch]
  (require-protocol impl/ComputeService compute 'nodes)
  (impl/nodes compute ch))

(defn targets
  "Return the targets from the compute service."
  [compute ch]
  (require-protocol impl/ComputeService compute 'targets)
  (go-try ch
    (let [c (chan)]
      (impl/nodes compute c)
      (let [[nodes e] (<! c)]
        (>! ch [(map #(hash-map :node %) nodes) e])))))

(defn create-nodes
  "Create nodes running in the compute service."
  [compute node-spec user node-count options ch]
  {:pre [(map? node-spec)(map? (:image node-spec))]}
  (require-protocol impl/ComputeServiceNodeCreateDestroy compute 'create-nodes)
  (impl/create-nodes compute node-spec user node-count options ch))

(defn destroy-nodes
  "Destroy the nodes running in the compute service. Return a sequence
  of node ids that have been destroyed."
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
  {:pre [(or (nil? image) (map? image))]}
  (check-node-spec (vary-meta (or options {}) assoc :type ::node-spec)))


;;; TODO move these into a knowledge base

;;; Hierarchies

;; TODO fix the no-check when derive has correct annotations
(def os-hierarchy
  (-> (make-hierarchy)
      (derive :linux :os)

      ;; base distibutions
      (derive :rh-base :linux)
      (derive :debian-base :linux)
      (derive :arch-base :linux)
      (derive :suse-base :linux)
      (derive :bsd-base :linux)
      (derive :gentoo-base :linux)

      ;; distibutions
      (derive :centos :rh-base)
      (derive :rhel :rh-base)
      (derive :amzn-linux :rh-base)
      (derive :fedora :rh-base)

      (derive :debian :debian-base)
      (derive :ubuntu :debian-base)
      (derive :jeos :debian-base)

      (derive :suse :suse-base)
      (derive :arch :arch-base)
      (derive :gentoo :gentoo-base)
      (derive :darwin :bsd-base)
      (derive :os-x :bsd-base)))

;;; target mapping
(def packager-map
  (version-map os-hierarchy :os :os-version
               {{:os :debian-base} :apt
                {:os :rh-base} :yum
                {:os :arch-base} :pacman
                {:os :gentoo-base} :portage
                {:os :suse-base} :zypper
                {:os :os-x} :brew
                {:os :darwin} :brew}))

(defn packager-for-os
  "Package manager"
  [os-family os-version]
  {:pre [(keyword? os-family)]}
  (or
   (get packager-map (maybe-assoc
                      {:os os-family}
                      :os-version (and os-version
                                       (as-version-vector os-version))))
   (throw
    (ex-info
     (format "Unknown packager for %s %s" os-family os-version)
     {:type :unknown-packager}))))

(defn admin-group
  "Default admin group for host"
  [os-family os-version]
  (case os-family
    :centos "wheel"
    :rhel "wheel"
    "adm"))
