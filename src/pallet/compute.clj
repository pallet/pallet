(ns pallet.compute
  "Abstraction of the compute interface"
  (:require
   [clojure.core.async :refer [<!! chan]]
   [clojure.core.typed
    :refer [ann
            AnyInteger Hierarchy Map Nilable NilableNonEmptySeq NonEmptySeqable
            Seq]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.core.types                   ; before any protocols
    :refer [GroupSpec GroupName Keyword ProviderIdentifier TargetMap
            User]]
   [pallet.compute.protocols :as impl :refer [Node]]
   [pallet.core.protocols :as core-impl]
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
