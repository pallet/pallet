(ns pallet.spec
  "Provides server-spec functionality.

A server-spec can specify phases that provide facets of a particular
service."
  (:require
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.compute :as compute]
   [pallet.core.node :refer [node?]]
   [pallet.core.node-os :refer [node-os]]
   [pallet.core.plan-state :as plan-state]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.phase :as phase :refer [phases-with-meta]]
   [pallet.plan :refer [execute]]
   [pallet.session :as session
    :refer [base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.target :as target]
   [pallet.utils :refer [maybe-update-in total-order-merge]]
   [schema.core :as schema :refer [check required-key optional-key validate]])
  (:import clojure.lang.IFn))

;;; # Domain Model

;;; ## Schemas

(def phases-schema
  {schema/Keyword IFn})

(def phase-meta-schema
  {(optional-key :middleware) IFn
   (optional-key :phase-middleware) IFn})

(def override-schema
  {(optional-key :packager) schema/Keyword
   (optional-key :os-family) schema/Keyword
   (optional-key :os-version) String})

(def phases-meta-schema
  {schema/Keyword phase-meta-schema})

(def server-spec-schema
  {(optional-key :phases) phases-schema
   (optional-key :roles) #{schema/Keyword}
   (optional-key :packager) schema/Keyword
   (optional-key :phases-meta) phases-meta-schema
   (optional-key :default-phases) [schema/Keyword]
   (optional-key :node-spec) compute/node-spec-schema
   (optional-key :override) override-schema})

(defn check-server-spec
  [m]
  (validate server-spec-schema m))

;;; ## Spec Merges
;;; When extending specs, we need to merge spec definitions.

(def ^:internal merge-spec-algorithm
  "Map from key to merge algorithm. Specifies how specs are merged."
  {:phases :merge-phases
   :roles :union
   :group-names :union
   :default-phases :total-ordering})

(defn ^:internal merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

(defn ^:internal extend-specs
  "Return spec with inherits, a sequence of specs, merged into it."
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
;;; Metadata for some phases defined by server-specs.
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
   - :packager       override the choice of packager to use

For a given phase, inherited phase functions are run first, in the order
specified in the `:extends` argument."
  [{:keys [phases phases-meta default-phases packager extends roles]
    :as options}]
  {:post [(check-server-spec %)]}
  (->
   (dissoc options :extends :phases-meta)
   (cond->
    roles (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
   (extend-specs extends)
   (maybe-update-in [:phases] phases-with-meta phases-meta default-phase-meta)
   (update-in [:default-phases] #(or default-phases % [:configure]))
   (vary-meta assoc :type ::server-spec)))

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
  {:pre [(target/has-node? target)]}
  (update-extension session targets-extension (fnil conj []) target))

(defn ^:internal set-targets
  "Set the target-maps in the session :groups extension."
  [session targets]
  {:pre [(every? target/has-node? targets)]}
  (set-extension session targets-extension targets))

(defn targets
  "Return the sequence of targets for the current operation.  The
  targets are recorded in the session groups extension."
  [session]
  (extension session targets-extension))

;;; # Map Nodes to Targets

;;; Given a sequence of nodes, we want to be able to return a sequence
;;; of target maps, where each target map is for a single node, and
;;; specifies the server-spec for that node.

(defn target-with-specs
  "Build a target from a target and a sequence of predicate, spec pairs.
  The returned target will contain all specs where the predicate
  returns true, merged in the order they are specified in the input
  sequence."
  [predicate-spec-pairs target]
  {:pre [(every? #(and (sequential? %)
                       (= 2 (count %))
                       (fn? (first %))
                       (map? (second %)))
                 predicate-spec-pairs)]}
  (reduce
   (fn [target [predicate spec]]
     (if (predicate target)
       (merge-specs merge-spec-algorithm target spec)
       target))
   target
   predicate-spec-pairs))
