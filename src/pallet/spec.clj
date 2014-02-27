(ns pallet.spec
  "Provides server-spec functionality.

A server-spec can specify phases that provide facets of a particular
service."
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of set-of wild]]
   [clojure.core.async :as async :refer [<! <!! >! chan]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.compute :as compute]
   [pallet.contracts :refer [check-spec]]
   [pallet.core.node-os :refer [node-os]]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.types
    :refer [BaseSession IncompleteGroupTargetMap IncompleteGroupTargetMapSeq
            Node Spec SpecSeq TargetMapSeq]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.node :as node :refer [node?]]
   [pallet.phase :as phase :refer [phases-with-meta]]
   [pallet.plan :refer [execute]]
   [pallet.session :as session
    :refer [base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.target :as target]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils :refer [combine-exceptions maybe-update-in total-order-merge]]
   [pallet.utils.async :refer [concat-chans go-try map-thread reduce-results]])
  (:import
   clojure.lang.IFn
   clojure.lang.Keyword))

;;; # Domain Model

;;; ## Schemas

(def-map-schema phases-schema
  [[(wild Keyword)] IFn])

(def-map-schema phase-meta-schema
  [(optional-path [:middleware]) IFn
   (optional-path [:phase-middleware]) IFn])

(def-map-schema override-schema
  [(optional-path [:packager]) Keyword
   (optional-path [:os-family]) Keyword
   (optional-path [:os-version]) String])

(def-map-schema phases-meta-schema
  [[(wild Keyword)] phase-meta-schema])

(def-map-schema server-spec-schema
  [(optional-path [:phases]) phases-schema
   (optional-path [:roles]) (set-of Keyword)
   (optional-path [:packager]) Keyword
   (optional-path [:phases-meta]) phases-meta-schema
   (optional-path [:default-phases]) (sequence-of Keyword)
   (optional-path [:node-spec]) compute/node-spec-schema
   (optional-path [:override]) override-schema])

(defmacro check-server-spec
  [m]
  (check-spec m `server-spec-schema &form))

;;; ## Spec Merges
;;; When extending specs, we need to merge spec defintions.

(def-alias KeyAlgorithms (Map Keyword Keyword))
(ann ^:no-check pallet.map-merge/merge-keys
     [KeyAlgorithms (Map Any Any) * -> (Map Any Any)])

(ann merge-spec-algorithm KeyAlgorithms)
(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-phases
   :roles :union
   :group-names :union
   :default-phases :total-ordering})

;; TODO remove :no-check
(ann ^:no-check merge-specs
     [KeyAlgorithms Spec Spec -> Spec]) ;; TODO do this properly with All and :>
(defn merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

(defn extend-specs
  "Merge in the inherited specs"
  ([spec inherits algorithms]
     (if inherits
       (merge-specs
        algorithms
        (if (map? inherits)
          inherits
          (reduce #(merge-specs algorithms %1 %2) {} inherits))
        spec)
       spec))
  ([spec inherits]
     (extend-specs spec inherits merge-spec-algorithm)))

;;; ## Phase Metadata
;;; Metadata for some phases defined by server-specs
(def ^{:doc "Executes on non bootstrapped nodes, with image credentials."}
  unbootstrapped-meta
  {:middleware (-> execute
                   middleware/image-user-middleware
                   (middleware/execute-on-unflagged :bootstrapped))})

(def ^{:doc "Executes on bootstrapped nodes, with admin user credentials."}
  bootstrapped-meta
  {:middleware (-> execute
                   (middleware/execute-on-flagged :bootstrapped))})

(def ^{:doc "The bootstrap phase is executed with the image credentials, and
only not flagged with a :bootstrapped keyword."}
  default-phase-meta
  {:bootstrap {:middleware (->
                            execute
                            middleware/image-user-middleware
                            (middleware/execute-one-shot-flag :bootstrapped))}})


;;; ## Server-spec

;;; TODO put node-spec under the :node-spec key

(defn server-spec
  "Create a server-spec.

   - :phases         a hash-map used to define phases. Phases are inherited by
                     anything that :extends the server-spec.
                     Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node
   - :default-phases a sequence specifying the default phases
   - :phases-meta    metadata to add to the phases
   - :extends        takes a server-spec, or sequence thereof, and is used to
                     inherit phases, etc.
   - :roles          defines a sequence of roles for the server-spec. Inherited
                     by anything that :extends the server-spec.
   - :node-spec      default node-spec for this server-spec
   - :packager       override the choice of packager to use

For a given phase, inherited phase functions are run first, in the order
specified in the `:extends` argument."
  [& {:keys [phases phases-meta default-phases packager node-spec extends roles]
      :as options}]
  {:post [(check-server-spec %)]}
  (check-server-spec
   (->
    (or node-spec {})                    ; ensure we have a map and not nil
    (merge options)
    (when-> roles
            (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
    (extend-specs extends)
    (maybe-update-in [:phases] phases-with-meta phases-meta default-phase-meta)
    (update-in [:default-phases] #(or default-phases % [:configure]))
    (dissoc :extends :node-spec :phases-meta)
    (vary-meta assoc :type ::server-spec))))

(ann default-phases [SpecSeq -> (Seqable Keyword)])
(defn default-phases
  "Return a sequence with the default phases for `specs`.  Applies a
  total ordering to the default-phases specified in all the specs."
  [specs]
  (->> specs
       (map :default-phases)
       distinct
       (apply total-order-merge)))

;;; # Targets Extension in Session

;;; In order to lookup group data given just a node, we write the
;;; target maps to the groups extension in the session.

(def ^{:doc "Keyword for the groups extension"
       :private true}
  targets-extension :groups)

(defn ^:internal add-target
  "Record the target-map in the session :groups extension."
  [session target]
  {:pre [(node? (target/node target))]}
  (update-extension session targets-extension (fnil conj []) target))

(defn ^:internal set-targets
  "Set the target-maps in the session :groups extension."
  [session targets]
  {:pre [(every? (comp node? target/node) targets)]}
  (set-extension session targets-extension targets))

(ann targets [BaseSession -> (Nilable TargetMapSeq)])
(defn targets
  "Return the sequence of targets for the current operation.  The
  targets are recorded in the session groups extension."
  [session]
  (extension session targets-extension))

;; (ann target-nodes [BaseSession -> (Seqable Node)])
;; (defn target-nodes
;;   "Target nodes for current converge."
;;   [session]
;;   (map (fn> [t :- TargetMap] (:node t)) (targets session)))


;;; # Map Nodes to Targets

;;; Given a sequence of nodes, we want to be able to return a sequence
;;; of target maps, where each target map is for a single node, and
;;; specifies the server-spec for that node.

;; TODO remove :no-check
(ann ^:no-check node->target
     [(Nilable (NonEmptySeqable '[[Node -> Boolean] Spec])) Node
      -> IncompleteGroupTargetMap])
(defn node->target
  "Build a target map from a node and a sequence of predicate, spec pairs.
  The target-map will contain all specs where the predicate returns
  true, merged in the order they are specified in the input sequence."
  [predicate-spec-pairs node]
  (reduce
   (fn [target [predicate spec]]
     (if (predicate node)
       ((fnil merge-specs {}) target spec)))
   {:node node}
   predicate-spec-pairs))

(ann node-targets
  [(Nilable (NonEmptySeqable '[[Node -> Boolean] Spec]))
   (NonEmptySeqable Node)
   -> IncompleteGroupTargetMapSeq])
(defn node-targets
  "For the sequence of nodes, nodes, return a sequence containing a
  merge of the specs matching each node.
  `predicate-spec-pairs` is a sequence of predicate, spec tuples."
  [predicate-spec-pairs nodes]
  {:pre [(every? #(and (sequential? %)
                       (= 2 (count %))
                       (fn? (first %))
                       (map? (second %))))
         (every? node? nodes)]}
  (map #(node->target predicate-spec-pairs %) nodes))
