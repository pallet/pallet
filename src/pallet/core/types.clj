(ns pallet.core.types
  "Type aliases"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.typed
    :refer [ann ann-protocol def-alias
            AnyInteger Atom1 Coll Map Nilable NilableNonEmptySeq NonEmptySeqable
            NonEmptyVec Set Seq Seqable]]
   [pallet.blobstore.protocols :refer [Blobstore]]
   [pallet.compute.protocols :refer [ComputeService Node]]
   [pallet.core.protocols :refer :all]
   [pallet.core.recorder.protocols :refer :all]
   [pallet.core.plan-state.protocols :refer :all])
  (:import
   clojure.lang.IMapEntry
   clojure.lang.PersistentHashSet))

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

(def-alias PlanFn
  "Type for plan functions"
  (Fn [Session -> Any]
      [Session Any * -> Any]))

(def-alias GroupSpec
  (HMap :mandatory {:group-name GroupName}
        :optional {:group-names (PersistentHashSet GroupName)
                   :image (HMap :mandatory {:os-family Keyword})
                   :node-filter [Node -> boolean]
                   :roles (PersistentHashSet Keyword)
                   :phases (Map Keyword PlanFn)
                   :count AnyInteger
                   :target-type (Value :group)}
        :absent-keys #{:node})
  ;; (HMap :mandatory {:group-name GroupName}
  ;;       :optional {:group-names (PersistentHashSet GroupName)
  ;;                  :image (HMap :mandatory {:os-family Keyword})
  ;;                  :node-filter [Node -> boolean]
  ;;                  :roles (PersistentHashSet Keyword)
  ;;                  :phases (Map Keyword [Any * -> Any])
  ;;                  :count AnyInteger
  ;;                  :target-type (Value :group)}
  ;;       :absent-keys #{:node})
  )

(def-alias ^:internal IncompleteTargetMap
  "A partial target map.  May not have any os details."
  (Assoc GroupSpec
         (Value :node) Node
         (Value :packager) Keyword)
  ;; (HMap :optional
  ;;       {:image (HMap :mandatory {:os-family Keyword})
  ;;        :group-names (Set GroupName)
  ;;        :roles Set
  ;;        :phases (Map Keyword [Any * -> Any])
  ;;        :packager Keyword}
  ;;       :mandatory
  ;;       {:node Node})
  )

(def-alias TargetMap
  "A target map is the denormalised combination of a group-spec and a node."
  ;; (Assoc IncompleteTargetMap
  ;;        (Value :node) Node
  ;;        (Value :image) (HMap :mandatory {:os-family Keyword}))
  (HMap :mandatory
        {:group-name GroupName
         :image (HMap :mandatory {:os-family Keyword})
         :node Node}
        :optional
        {:roles (PersistentHashSet Keyword)
         :group-names (PersistentHashSet GroupName)
         :packager Keyword
         :phases (Map Keyword PlanFn)}))

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

(ann-protocol pallet.compute.protocols/ComputeService
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

(ann-protocol pallet.compute.protocols/ComputeServiceProperties
  service-properties [ComputeService -> Map])

(ann-protocol pallet.compute.protocols/NodeTagReader
  node-tag (Fn [ComputeService Node String -> String]
               [ComputeService Node String String -> String])
  node-tags [ComputeService Node -> (Map String String)])

(ann-protocol pallet.compute.protocols/NodeTagWriter
  tag-node! [ComputeService Node String String -> nil]
  node-taggable? [ComputeService Node -> Boolean])

(ann-protocol pallet.compute.protocols/Blobstore
  sign-blob-request [Blobstore String String Map -> Map]
  put [Blobstore String String Any -> nil]
  put-file [Blobstore String String String -> nil]
  containers [Blobstore -> (Seq String)]
  close-blobstore [Blobstore -> nil])

(ann-protocol pallet.compute.protocols/Node
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

(ann-protocol pallet.compute.protocols/NodePackager
  packager [Node -> Keyword])

(ann-protocol pallet.compute.protocols/NodeImage
  image-user [Node -> User])

(ann-protocol pallet.compute.protocols/NodeHardware
  hardware [Node -> Hardware])

(ann-protocol pallet.compute.protocols/NodeProxy
  proxy [Node -> Proxy])

(ann pallet.core.protocols/channel? [Any -> Boolean])

(def-alias TargetMapSeq
  "Describes the nodes that are available."
  ;; (Nilable (NonEmptySeqable TargetMap))
  (Seqable TargetMap))

(def-alias IncompleteTargetMapSeq
  "Describes the nodes that are available."
  (Nilable (NonEmptySeqable IncompleteTargetMap)))

(def-alias ScopeMap
  (Map Keyword Any))

;; TODO - use (HVec Keyword Any) as key type when core.typed doesn't crash on it
(def-alias ScopeValue
  (IMapEntry Any Any))

(ann-protocol pallet.core.plan-state.protocols/StateGet
  get-state [StateGet ScopeMap (NonEmptyVec Keyword) Any
             -> (Map Any Any)]) ;; TODO wanted (HVec Keyword Any) as key type

(ann-protocol pallet.core.plan-state.protocols/StateUpdate
  update-state [StateUpdate Keyword Any (Fn [Any * -> Any])
                (Nilable (NonEmptySeqable Any))
                -> Any])

(def-alias PlanState
  "The plan-state holds arbitrary data."
  pallet.core.plan-state.protocols/StateGet)

(def-alias ^:internal Action
  "Representation of an instance of an action to be executed."
  (HMap))

(def-alias ActionOptions
  (HMap :optional {:script-dir String
                   :sudo-user String
                   :script-prefix Keyword
                   :script-env (HMap)
                   :script-comments boolean
                   :new-login-after-action boolean}))

(def-alias ActionErrorMap
  "Represents details of any error that might occur in executing an action."
  (HMap :optional
        {:exception Throwable
         :message String
         :timeout boolean}))

(def-alias ActionResult
  "The result of executing an action."
  (HMap :optional
        {:out String
         :err String
         :exit AnyInteger
         :error ActionErrorMap}))

(def-alias Executor (Fn [Session Action -> ActionResult]))

(def-alias ExecuteStatusFn (Fn [ActionResult -> ActionResult]))

(def-alias Recorder pallet.core.recorder.protocols/Record)

(def-alias ExecutionState
  (HMap :mandatory {:executor Executor
                    :execute-status-fn ExecuteStatusFn
                    :recorder Recorder
                    :action-options ActionOptions}))

(def-alias BaseSession
  (HMap :mandatory {:execution-state ExecutionState
                    :plan-state StateGet
                    :system-targets (Atom1 (Nilable TargetMapSeq))
                    :type (Value :pallet.core.session/session)}))

(def-alias Session
  "The pallet session state."
  ;; (HMap :mandatory {:execution-state ExecutionState
  ;;                   :plan-state StateGet
  ;;                   :system-targets (Atom1
  ;;                                    (Nilable (NonEmptySeqable TargetMap)))
  ;;                   :type (Value :pallet.core.session/session)
  ;;                   :target TargetMap})
  (Assoc BaseSession (Value :target) TargetMap)
  ;; (HMap
  ;;  :mandatory
  ;;  {:plan-state PlanState
  ;;   :environment EnvironmentMap
  ;;   :service-state TargetMapSeq
  ;;   :server TargetMap
  ;;   :pallet.core.api/executor [Session Action -> '[ActionResult Session]]
  ;;   :pallet.core.api/execute-status-fn [ActionResult -> nil]
  ;;   :user User}
  ;;  :optional
  ;;  {:results (NonEmptySeqable PhaseResult)
  ;;   :phase-results (NilableNonEmptySeq ActionResult)
  ;;   :pallet.phase/session-verification boolean})
  )

(def-alias EnvironmentMap
  "Describes some well known keys in the environment map."
  (HMap :mandatory {:user User}
        :optional {:algorithms
                   (HMap :optional
                         {:executor Executor
                          :execute-status-fn ExecuteStatusFn})
                   :provider-options (Map Any Any)}))

(ann-protocol pallet.core.protocols/Environment
  environment [Environment -> EnvironmentMap])

(def-alias Phase
  "Describes the invocation of a phase."
  (U Keyword (Vector* Keyword Any *)))

(def-alias PhaseResult
  "Describe the result of executing a phase on a target."
  (HMap
   :mandatory {:action-results (NilableNonEmptySeq ActionResult)
               :target TargetMap}
   :optional {:error ActionErrorMap
              :return-value Any}))

(def-alias ErrorMap
  (TFn [[x :variance :covariant]]
       (HMap :mandatory {:error (HMap :mandatory {:exception Throwable})
                         :target x})))

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
                    :service-state TargetMapSeq
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

(ann-protocol pallet.core.recorder.protocols/Record
  record [Record ActionResult -> Any])

(ann-protocol pallet.core.recorder.protocols/Results
  results [Results -> (Nilable (NonEmptySeqable ActionResult))])

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
