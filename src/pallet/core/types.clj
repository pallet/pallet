(ns pallet.core.types
  "Type aliases"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.core.typed
    :refer [ann ann-protocol def-alias
            AnyInteger Map Nilable NilableNonEmptySeq NonEmptySeqable
            NonEmptyVec Set Seq Seqable]]
   [pallet.core.protocols :refer :all]))

(ann-protocol pallet.core.protocols/Status
  status [Status -> (Seqable Any)])

(ann-protocol pallet.core.protocols/Abortable
  abort! [Abortable Any -> nil])

(ann-protocol pallet.core.protocols/StatusUpdate
  status! [StatusUpdate Any -> nil])

(ann-protocol pallet.core.protocols/DeliverValue
  deliver! [DeliverValue Any -> nil])

(ann ^:no-check pallet.core.protocols/operation? [Any -> Boolean])

(ann-protocol pallet.core.protocols/Environment
  environment [Environment -> Any])

(def-alias Keyword clojure.lang.Keyword)
(def-alias Symbol clojure.lang.Symbol)
(def-alias MapEntry clojure.lang.IMapEntry)
;; (def-alias Hierarchy (HMap :mandatory
;;                            {:parents (HMap)
;;                             :descendants (HMap)
;;                             :ancestors (HMap)}))
(def-alias ProviderIdentifier Keyword)
(def-alias ServiceIdentifier Keyword)

(def-alias OsDetailsMap (HMap :mandatory {:os-family Keyword}
                              :optional {:os-version String
                                         :packager Keyword}))
(def-alias GroupName Keyword)
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

(def-alias IncompleteTargetMap
  (HMap :optional
        {:image (HMap :mandatory {:os-family Keyword})
         :group-names (Set GroupName)
         :roles Set
         :phases (Map Keyword [Any * -> Any])
         :packager Keyword}
        :mandatory
        {:node Node}))

(def-alias TargetMap (HMap :mandatory
                           {:group-name GroupName
                            :image (HMap :mandatory {:os-family Keyword})
                            :node Node}
                           :optional
                           {:roles Set
                            :group-names (Set GroupName)
                            :packager Keyword
                            :phases (Map Keyword [Any * -> Any])}))

;; (def-alias PhaseTarget
;;   (HMap :operational {:group GroupSpec
;;                       :server TargetMap}))
(def-alias PhaseTarget (U GroupSpec TargetMap))

(def-alias User
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

(def-alias Hardware (HMap))

(ann-protocol pallet.core.protocols/NodeHardware
  hardware [Node -> Hardware])

(def-alias Proxy (HMap))

(ann-protocol pallet.core.protocols/NodeProxy
  proxy [Node -> Proxy])

(ann pallet.core.protocols/channel? [Any -> Boolean])

(def-alias ServiceState
  (NonEmptySeqable TargetMap))

(def-alias IncompleteServiceState
  (Nilable (NonEmptySeqable IncompleteTargetMap)))

(def-alias PlanState (Map Any Any))

(def-alias Action (HMap))
(def-alias ActionErrorMap (HMap :optional
                                {:exception Throwable
                                 :message String}))
(def-alias ActionResult (HMap :optional
                              {:out String
                               :err String
                               :exit AnyInteger
                               :error ActionErrorMap}))

(def-alias EnvironmentMap
  (HMap :mandatory {:user User}
        :optional {:algorithms
                   (HMap :optional
                         {:executor Executor
                          :execute-status-fn [ActionResult -> ActionResult]})
                   :provider-options (Map Any Any)}))

(def-alias Phase (U Keyword
                    '[Keyword Any]
                    '[Keyword Any Any]
                    '[Keyword Any Any Any]
                    '[Keyword Any Any Any Any]))

(def-alias PhaseResult
  (HMap
   :mandatory {:result (NilableNonEmptySeq ActionResult)}
   :optional {:error ActionErrorMap}))

(def-alias Session
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

(def-alias Executor [Session Action -> ActionResult])
(def-alias ExecSettings (HMap
                         :mandatory
                         {:user User
                          :executor Executor
                          :execute-status-fn [ActionResult -> ActionResult]}))
(def-alias ExecSettingsFn [EnvironmentMap PhaseTarget -> ExecSettings])

(def-alias TargetPhaseResult
  (HMap :mandatory {:plan-state PlanState
                    :environment EnvironmentMap
                    :service-state ServiceState
                    :pallet.core.api/executor [Session Action -> ActionResult]
                    :pallet.core.api/execute-status-fn [ActionResult -> nil]}))


(def-alias ServiceState (Seq TargetMap))

(def-alias Result
  "Overall result of a lift or converge."
  (Nilable (NonEmptySeqable PhaseResult)))

(def-alias SettingsOptions
  (HMap :optional {:instance-id Keyword
                   :default Any}))

(def-alias VersionVector (NonEmptyVec Number))
(def-alias VersionRange (U '[VersionVector] '[VersionVector VersionVector]))
(def-alias VersionSpec (U VersionVector VersionRange))

(def-alias OsVersionMap
  (HMap :optional {:os Keyword
                   :os-version VersionSpec
                   :version VersionSpec}))

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

(defmacro assert-object-or-nil [x]
  `(let [x# ~x]
     (assert (or (instance? Object x#) (nil? x#)))
     x#))

(defmacro assert-not-nil [x]
  `(let [x# ~x]
     (assert (not (nil? x#)))
     x#))

(defmacro assert-instance
  [type-sym x]
  `(let [x# ~x]
     (assert (instance? ~type-sym x#))
     x#))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (doseq> 1)(fn> 1)(for> 1)(loop> 1))
;; End:
