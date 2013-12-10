(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [clojure.core.typed
    :refer [ann
            AnyInteger Hierarchy Map Nilable NilableNonEmptySeq NonEmptySeqable
            Seq]]
   [pallet.core.type-annotations]
   [pallet.core.types                   ; before any protocols
    :refer [GroupSpec GroupName Keyword ProviderIdentifier TargetMap
            User]]
   [pallet.compute.protocols :as impl :refer [ComputeService Node]]
   [pallet.compute.implementation :as implementation]
   [pallet.core.version-dispatch :refer [version-map]]
   [pallet.utils :refer [maybe-assoc]]
   [pallet.versions :refer [as-version-vector]]))

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

;;; Actions

;; TODO
;; the executor should be passed to the compute service to allow remote
;; execution of the init script using the executor abstraction.

;; However, the executor uses the session abstraction, so that would need
;; passing too
(ann nodes [ComputeService -> (Nilable (NonEmptySeqable Node))])
(defn nodes [compute]
  (impl/nodes compute))

(ann run-nodes [ComputeService NodeSpec User AnyInteger
                -> (Nilable (NonEmptySeqable Node))])
(defn run-nodes
  "Start node-count nodes for group-spec, executing an init-script on
  each, using the specified user and options."
  [compute node-spec user node-count]
  (impl/run-nodes compute node-spec user node-count))

(ann tag-nodes [ComputeService (Seqable Node) Tags ->
                (Nilable (NonEmptySeqable Node))])
(defn tag-nodes
  "Set the `tags` on all `nodes`."
  [compute nodes tags]
  (impl/tag-nodes compute nodes tags))

(ann reboot [ComputeService (Seq Node) -> nil])
(defn reboot
  "Reboot the specified nodes"
  [compute nodes]
  (impl/reboot compute nodes))

(ann boot-if-down [ComputeService (Seq Node) -> nil])
(defn boot-if-down
  "Boot the specified nodes, if they are not running."
  [compute nodes]
  (impl/boot-if-down compute nodes))

(ann shutdown-node [ComputeService Node User -> nil])
(defn shutdown-node
  "Shutdown a node."
  [compute node user]
  (impl/shutdown-node compute node user))

(ann shutdown [ComputeService (Seq Node) User -> nil])
(defn shutdown
  "Shutdown specified nodes"
  [compute nodes user]
  (impl/shutdown compute nodes user))

(ann ensure-os-family [ComputeService GroupSpec -> nil])
(defn ensure-os-family
 "Called on startup of a new node to ensure group-spec has an os-family
   attached to it."
 [compute group-spec]
 (impl/ensure-os-family compute group-spec))

(ann destroy-nodes [ComputeService Nodes -> nil])
(defn destroy-nodes
  [compute nodes]
  (impl/destroy-nodes compute nodes))

(ann destroy-node [ComputeService Node -> nil])
(defn destroy-node
  [compute node]
  (impl/destroy-node compute node))

(ann images [ComputeService -> (Seq Map)])
(defn images [compute]
  (impl/images compute))

(ann close [ComputeService -> nil])
(defn close [compute]
  (impl/close compute))

(ann ^:no-check compute-service? [Any -> boolean])
(defn compute-service?
  "Predicate for the argument satisfying the ComputeService protocol."
  [c]
  (satisfies? pallet.compute.protocols/ComputeService c))

(ann service-properties [ComputeService -> Map])
(defn service-properties
  "Return a map of service details.  Contains a :provider key at a minimum.
  May contain current credentials."
  [compute]
  (impl/service-properties compute))

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
