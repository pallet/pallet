(ns pallet.core.types
  "Type aliases"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.typed
    :refer [ann ann-protocol def-alias
            AnyInteger Map Nilable NilableNonEmptySeq NonEmptySeqable
            NonEmptyVec Set Seq Seqable]]
   [pallet.core.protocols :refer :all]))

;;; # Pallet Types

;; This provides the types used in Pallet.  There may be some other
;; types declared in other namespaces, when they are used only within
;; a single namespace.

(ann-protocol pallet.core.protocols/Status
  status [Status -> (Seqable Any)])

(ann-protocol pallet.core.protocols/Abortable
  abort! [Abortable Any -> nil])

(ann-protocol pallet.core.protocols/StatusUpdate
  status! [StatusUpdate Any -> nil])

(ann-protocol pallet.core.protocols/DeliverValue
  deliver! [DeliverValue Any -> nil])

(ann ^:no-check pallet.core.protocols/operation? [Any -> Boolean])

(def-alias Keyword
  "A simple alias for clojure.lang.Keyword"
  clojure.lang.Keyword)
(def-alias Symbol
  "A simple alias for clojure.lang.Symbol"
  clojure.lang.Symbol)

(def-alias ProviderIdentifier
  "Pallet providers are identified using keywords."
  Keyword)

(def-alias ServiceIdentifier
  "Pallet services are identified using keywords."
  Keyword)

(def-alias ^:internal OsDetailsMap
  "Map type used to represent the os details of a target node."
  (HMap :mandatory {:os-family Keyword}
        :optional {:os-version String
                   :packager Keyword}))

(def-alias GroupName
  "Pallet group names are keywords."
  Keyword)

(def-alias GroupSpec
  (HMap :mandatory {:group-name GroupName}
        :optional {:group-names (Set GroupName)
                   :image (HMap :mandatory {:os-family Keyword})
                   :node-filter [Node -> boolean]
                   :roles Set
                   :phases (Map Keyword [Any * -> Any])
                   :count AnyInteger
                   :target-type (Value :group)}
        :absent-keys #{:node}))

(def-alias ^:internal IncompleteTargetMap
  "A partial target map.  May not have any os details."
  (HMap :optional
        {:image (HMap :mandatory {:os-family Keyword})
         :group-names (Set GroupName)
         :roles Set
         :phases (Map Keyword [Any * -> Any])
         :packager Keyword}
        :mandatory
        {:node Node}))

(def-alias TargetMap
  "A target map is the denormalised combination of a group-spec and a node."
  (HMap :mandatory
        {:group-name GroupName
         :image (HMap :mandatory {:os-family Keyword})
         :node Node}
        :optional
        {:roles Set
         :group-names (Set GroupName)
         :packager Keyword
         :phases (Map Keyword [Any * -> Any])}))

(def-alias ^:internal PhaseTarget
  "Phases are run against a group or against a specific node."
  (U GroupSpec TargetMap))

(def-alias User
  "Describes a user for logging in to nodes, and executing commands as
a priviledged user."
  (HMap :mandatory {:username String}
        :optional {:public-key-path String
                   :private-key-path String
                   :public-key String
                   :private-key String
                   :passphrase String
                   :password String
                   :sudo-password String
                   :no-sudo boolean
                   :sudo-user String
                   :temp-key boolean}))

(def-alias Hardware
  "A description of the hardware on a node."
  (HMap))

(def-alias Proxy
  "A description of how to proxy into a node."
  (HMap))

(ann-protocol pallet.core.protocols/ComputeService
  nodes [ComputeService -> (Nilable (NonEmptySeqable Node))]
  run-nodes [ComputeService GroupSpec AnyInteger User (Nilable String)
             (Map Any Any) -> (Nilable (NonEmptySeqable Node))]
  reboot [ComputeService (Seq Node) -> nil]
  boot-if-down [ComputeService (Seq Node) -> nil]
  shutdown-node [ComputeService Node User -> nil]
  shutdown [ComputeService (Seq Node) User -> nil]
  ensure-os-family [ComputeService GroupSpec -> nil]
  destroy-nodes-in-group [ComputeService GroupName -> nil]
  destroy-node [ComputeService Node -> nil]
  images [ComputeService -> (Seq Map)]
  close [ComputeService -> nil])

(ann-protocol pallet.core.protocols/ComputeServiceProperties
  service-properties [ComputeService -> Map])

(ann-protocol pallet.core.protocols/NodeTagReader
  node-tag (Fn [ComputeService Node String -> String]
               [ComputeService Node String String -> String])
  node-tags [ComputeService Node -> (Map String String)])

(ann-protocol pallet.core.protocols/NodeTagWriter
  tag-node! [ComputeService Node String String -> nil]
  node-taggable? [ComputeService Node -> Boolean])

(ann-protocol pallet.core.protocols/Blobstore
  sign-blob-request [Blobstore String String Map -> Map]
  put [Blobstore String String Any -> nil]
  put-file [Blobstore String String String -> nil]
  containers [Blobstore -> (Seq String)]
  close-blobstore [Blobstore -> nil])

(ann-protocol pallet.core.protocols/Node
  ssh-port [Node -> AnyInteger]
  primary-ip [Node -> String]
  private-ip [Node -> String]
  is-64bit? [Node -> boolean]
  group-name [Node -> GroupName]
  hostname [Node -> String]
  os-family [Node -> Keyword]
  os-version [Node -> String]
  running? [Node -> boolean]
  terminated? [Node -> boolean]
  id [Node -> String]
  compute-service [Node -> ComputeService])

(ann-protocol pallet.core.protocols/NodePackager
  packager [Node -> Keyword])

(ann-protocol pallet.core.protocols/NodeImage
  image-user [Node -> User])

(ann-protocol pallet.core.protocols/NodeHardware
  hardware [Node -> Hardware])

(ann-protocol pallet.core.protocols/NodeProxy
  proxy [Node -> Proxy])

(ann pallet.core.protocols/channel? [Any -> Boolean])

(def-alias ServiceState
  "Describes the nodes that are available."
  (Nilable (NonEmptySeqable TargetMap)))

(def-alias IncompleteServiceState
  "Describes the nodes that are available."
  (Nilable (NonEmptySeqable IncompleteTargetMap)))

(def-alias PlanState
  "The plan-state holds arbitrary data."
  (Map Any Any))

(def-alias ^:internal Action
  "Representation of an instance of an action to be executed."
  (HMap))

(def-alias ActionErrorMap
  "Represents details of any error that might occur in executing an action."
  (HMap :optional
        {:exception Throwable
         :message String}))

(def-alias ActionResult
  "The result of executing an action."
  (HMap :optional
        {:out String
         :err String
         :exit AnyInteger
         :error ActionErrorMap}))

(def-alias EnvironmentMap
  "Describes some well known keys in the environment map."
  (HMap :mandatory {:user User}
        :optional {:algorithms
                   (HMap :optional
                         {:executor Executor
                          :execute-status-fn [ActionResult -> ActionResult]})
                   :provider-options (Map Any Any)}))

(ann-protocol pallet.core.protocols/Environment
  environment [Environment -> EnvironmentMap])

;; TODO - update to use variable arity Vector* when core.typed supports it.
(def-alias Phase
  "Describes the invocation of a phase."
  (U Keyword
     '[Keyword Any]
     '[Keyword Any Any]
     '[Keyword Any Any Any]
     '[Keyword Any Any Any Any]))

(def-alias PhaseResult
  "Describe the result of executing a phase on a target."
  (HMap
   :mandatory {:result (NilableNonEmptySeq ActionResult)}
   :optional {:error ActionErrorMap}))

(def-alias Session
  "The result of a lift or converge."
  (HMap
   :mandatory
   {:plan-state PlanState
    :environment EnvironmentMap
    :service-state ServiceState
    :server TargetMap
    :pallet.core.api/executor [Session Action -> '[ActionResult Session]]
    :pallet.core.api/execute-status-fn [ActionResult -> nil]
    :user User}
   :optional
   {:results (NonEmptySeqable PhaseResult)
    :phase-results (NilableNonEmptySeq ActionResult)
    :pallet.phase/session-verification boolean}))

(def-alias Executor
  "Function type for a function that can execute actions."
  [Session Action -> ActionResult])

(def-alias ExecSettings
  "Describes details required to be able to execute an action."
  (HMap
   :mandatory
   {:user User
    :executor Executor
    :execute-status-fn [ActionResult -> ActionResult]}))

(def-alias ExecSettingsFn
  "A function type that returns details needed for execution."
  [EnvironmentMap PhaseTarget -> ExecSettings])

(def-alias TargetPhaseResult
  "The result of executing a phase on a target."
  (HMap :mandatory {:plan-state PlanState
                    :environment EnvironmentMap
                    :service-state ServiceState
                    :pallet.core.api/executor [Session Action -> ActionResult]
                    :pallet.core.api/execute-status-fn [ActionResult -> nil]}))

(def-alias ^:internal Result
  "Overall result of a lift or converge."
  (Nilable (NonEmptySeqable PhaseResult)))

(def-alias SettingsOptions
  (HMap :optional {:instance-id Keyword
                   :default Any}))

(def-alias VersionVector
  "A vector of numbers representing a dotted version number."
  (NonEmptyVec Number))

(def-alias VersionRange
  "A version range."
  (U '[VersionVector] '[(U nil VersionVector) (U nil VersionVector)]))

(def-alias VersionSpec
  "A version spec is either a version vector or a version range."
  (U VersionVector VersionRange))

(def-alias OsVersionMap
  "Represents versions in version dispatched functions"
  (HMap :optional {:os Keyword
                   :os-version VersionSpec
                   :version VersionSpec}))

;;; # Typing Utilities

(ann ^:no-check keyword-map? (predicate (Map Keyword Any)))
(defn keyword-map?
  "Predicate to check for map from keyword to any value"
  [m]
  (and (map? m) (every? keyword? (keys m))))

(defmacro assert-type-predicate
  "Assert that the runtime predicate p, which should have a type
  including a filter, is true, when applied to x.  Returns x."
  [x p]
  `(let [x# ~x]
     (assert (~p x#) (str "Failed " ~(pr-str p) ": " (pr-str x#)))
     x#))

(defmacro assert-object-or-nil
  "Inline assertion of (U Object nil).  Useful for the return value of
implementations of java functions in deftypes, which otherwise
complain that they can't return values of type Any."
  [x]
  `(let [x# ~x]
     (assert (or (instance? Object x#) (nil? x#)))
     x#))

(defmacro assert-not-nil
  "Inline assertion that a value is not nil."
  [x]
  `(let [x# ~x]
     (assert (not (nil? x#)))
     x#))

(defmacro assert-instance
  "Inline assertion that a value is an instance of the specified java type."
  [type-sym x]
  `(let [x# ~x]
     (assert (instance? ~type-sym x#))
     x#))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (doseq> 1)(fn> 1)(for> 1)(loop> 1))
;; End:
