(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of wild]]
   [clojure.core.async :as async :refer [<!! <! >! chan]]
   [clojure.core.typed
    :refer [ann
            AnyInteger Hierarchy Map Nilable NilableNonEmptySeq NonEmptySeqable
            Seq]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.core.types                   ; before any protocols
    :refer [GroupSpec GroupName Keyword ProviderIdentifier TargetMap
            User]]
   [pallet.compute.protocols :as impl :refer [Node]]
   [pallet.compute.implementation :as implementation]
   [pallet.contracts :refer [check-spec]]
   [pallet.core.protocols :as core-impl]
   [pallet.core.version-dispatch :refer [version-map]]
   [pallet.utils :refer [maybe-assoc]]
   [pallet.utils.async :refer [go-try]]
   [pallet.versions :refer [as-version-vector]]))

;;; ## Schema types

;;; node-spec contains loose schema, as these vary by, and should be enforced by
;;; the providers.
(def-map-schema :loose image-spec-schema
  [(optional-path [:image-id]) [:or String Keyword]
   (optional-path [:image-description-matches]) String
   (optional-path [:image-name-matches]) String
   (optional-path [:image-version-matches]) String
   (optional-path [:os-family]) Keyword
   (optional-path [:os-64-bit]) wild
   (optional-path [:os-arch-matches]) String
   (optional-path [:os-description-matches]) String
   (optional-path [:os-name-matches]) String
   (optional-path [:os-version-matches]) String
   (optional-path [:hypervisor-matches]) String
   (optional-path [:override-login-user]) String])

(def-map-schema :loose location-spec-schema
  [(optional-path [:location-id]) String])

(def-map-schema :loose hardware-spec-schema
  [(optional-path [:hardware-id]) String
   (optional-path [:min-ram]) Number
   (optional-path [:min-cores]) Number
   (optional-path [:min-disk]) Number])

(def-map-schema inbound-port-spec-schema
  [[:start-port] Number
   (optional-path [:end-port]) Number
   (optional-path [:protocol]) String])

(def inbound-port-schema
  [:or inbound-port-spec-schema Number])

(def-map-schema :loose network-spec-schema
  [(optional-path [:inbound-ports]) (sequence-of inbound-port-schema)])

(def-map-schema :loose qos-spec-schema
  [(optional-path [:spot-price]) Number
   (optional-path [:enable-monitoring]) wild])

(def-map-schema node-spec-schema
  [(optional-path [:image]) image-spec-schema
   (optional-path [:location]) location-spec-schema
   (optional-path [:hardware]) hardware-spec-schema
   (optional-path [:network]) network-spec-schema
   (optional-path [:qos]) qos-spec-schema
   (optional-path [:provider]) (map-schema :loose [])])

(defmacro check-node-spec
  [m]
  (check-spec m `node-spec-schema &form))


;;; Meta
(ann supported-providers [-> (NilableNonEmptySeq ProviderIdentifier)])
(defn supported-providers
  "Return a list of supported provider names.
Each name is suitable to be passed to compute-service."
  []
  (implementation/supported-providers))

;;; Compute Service instantiation
(ann missing-provider-re java.util.regex.Pattern)
(def ^:private
  missing-provider-re
  #"No method in multimethod 'service' for dispatch value: (.*)")

(ann instantiate-provider
     [ProviderIdentifier & :optional {:identity String :credential String}
      -> pallet.compute.protocols/ComputeService])
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

(ann ^:no-check compute-service? [Any -> boolean])
(defn compute-service?
  "Predicate for the argument satisfying the ComputeService protocol."
  [c]
  (satisfies? pallet.compute.protocols/ComputeService c))

;;; Actions

(defmacro defasync
  "Define a function with a synchronous and an asynchronous
  overloading.  The last argument is expected to be a channel, which
  is removed from the argument list for the synchronous overload. If
  there is ::protocol metadata, then this is used to implement a
  run-time check that the first argument satisfies the protocol."
  {:arglists '[[name doc-string? attr-map? [params*] prepost-map? body]]}
  [name args & body]
  (let [[name [args & body]] (name-with-attributes name (concat [args] body))
        p (-> name meta ::protocol)
        name (vary-meta name dissoc ::protocol)]
    `(defn ~name
       (~(vec (butlast args))
        (let [ch# (chan)]
          (~name ~@(butlast args) ch#)
          (let [[r# e#] (<!! ch#)]
            (when e#
              (throw e#))
            r#)))
       (~args
        ~@(if p [`(require-protocol ~p ~(first args) '~name)])
        ~@body))))

(defn- unsupported-exception [service operation]
  (ex-info "Unsupported Operation"
           {:service service
            :operation operation}))

(defn- require-protocol [protocol service operation]
  (when-not (satisfies? protocol service)
    (throw (unsupported-exception service operation))))

(defasync nodes
  "Return the nodes in the compute service."
  {::protocol impl/ComputeService}
  [compute ch]
  (impl/nodes compute ch))

(defasync targets
  "Return the targets from the compute service."
  {::protocol impl/ComputeService}
  [compute ch]
  (go-try ch
    (let [c (chan)]
      (impl/nodes compute c)
      (let [[nodes e] (<! c)]
        (>! ch [(map #(hash-map :node %) nodes) e])))))

(defasync create-nodes
  "Create nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeCreateDestroy}
  [compute node-spec user node-count options ch]
  {:pre [(map? node-spec)(map? (:image node-spec))]}
  (impl/create-nodes compute node-spec user node-count options ch))

(defasync destroy-nodes
  "Destroy the nodes running in the compute service. Return a sequence
  of node ids that have been destroyed."
  {::protocol impl/ComputeServiceNodeCreateDestroy}
  [compute nodes ch]
  (impl/destroy-nodes compute nodes ch))

(defasync images
  "Return the images available in the compute service."
  {::protocol impl/ComputeServiceNodeCreateDestroy}
  [compute ch]
  (impl/images compute ch))

(defasync restart-nodes
  "Start the nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeStop}
  [compute nodes ch]
  (impl/restart-nodes compute nodes ch))

(defasync stop-nodes
  "Stop the nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeStop}
  [compute nodes ch]
  (impl/stop-nodes compute nodes ch))

(defasync suspend-nodes
  "Suspend the nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeSuspend}
  [compute nodes ch]
  (impl/suspend-nodes compute nodes ch))

(defasync resume-nodes
  "Resume the nodes running in the compute service."
  {::protocol impl/ComputeServiceNodeSuspend}
  [compute nodes ch]
  (impl/resume-nodes compute nodes ch))

(ann tag-nodes [ComputeService (Seqable Node) Tags ->
                (Nilable (NonEmptySeqable Node))])
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

(ann close [ComputeService -> nil])
(defn close
  "Close the compute service, releasing any acquired resources."
  [compute]
  (require-protocol core-impl/Closeable compute :close)
  (core-impl/close compute))

(ann service-properties [ComputeService -> Map])
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
  [& {:keys [image hardware location network qos] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (check-node-spec (vary-meta (or options {}) assoc :type ::node-spec)))

;;; Hierarchies

(ann ^:no-check os-hierarchy Hierarchy)
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

(defmacro defmulti-os
  "Defines a defmulti used to abstract over the target operating system. The
   function dispatches based on the target operating system, that is extracted
   from the session passed as the first argument.

   Version comparisons are not included"
  [name [& args]]
  `(do
     (defmulti ~name
       (fn [~@args] (-> ~(first args) :server :image :os-family))
       :hierarchy #'os-hierarchy)

     (defmethod ~name :default [~@args]
       (throw
        (ex-info
         (format
          "%s does not support %s"
          ~name (-> ~(first args) :server :image :os-family))
         {:type :pallet/unsupported-os})))))

;;; target mapping
(ann packager-map pallet.core.version_dispatch.VersionMap)
(def packager-map
  (version-map os-hierarchy :os :os-version
               {{:os :debian-base} :apt
                {:os :rh-base} :yum
                {:os :arch-base} :pacman
                {:os :gentoo-base} :portage
                {:os :suse-base} :zypper
                {:os :os-x} :brew
                {:os :darwin} :brew}))

(ann packager-for-os [Keyword (Nilable String) -> Keyword])
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

(ann admin-group (Fn [TargetMap -> String]
                     [Keyword (Nilable String) -> String]))
(defn admin-group
  "Default admin group for host"
  ([target]
     (admin-group (-> target :image :os-family) nil))
  ([os-family os-version]
     (case os-family
       :centos "wheel"
       :rhel "wheel"
       "adm")))
