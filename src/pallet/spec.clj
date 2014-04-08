(ns pallet.spec
  "Provides server-spec functionality.

A server-spec can specify phases that provide facets of a particular
service."
  (:require
   [taoensso.timbre :as logging :refer [debugf tracef]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.node :as node]
   [pallet.plan :refer [execute-plan*]]
   [pallet.session :as session
    :refer [extension set-extension update-extension]]
   [pallet.utils :refer [maybe-update-in total-order-merge]]
   [schema.core :as schema :refer [check required-key optional-key validate]])
  (:import clojure.lang.IFn))

;;; # Domain Model

;;; ## Schemas

(def PhaseMap
  {schema/Keyword IFn})

(def PhaseMeta
  {(optional-key :middleware) IFn
   (optional-key :phase-middleware) IFn})

(def NodeOverride
  {(optional-key :packager) schema/Keyword
   (optional-key :os-family) schema/Keyword
   (optional-key :os-version) String})

(def PhasesMeta
  {schema/Keyword PhaseMeta})

(def ServerSpec
  {(optional-key :phases) PhaseMap
   (optional-key :roles) #{schema/Keyword}
   (optional-key :packager) schema/Keyword
   (optional-key :phases-meta) PhasesMeta
   (optional-key :default-phases) [schema/Keyword]
   (optional-key :override) NodeOverride})

(def ExtendedServerSpec
  (assoc ServerSpec
    ;; allow extensions to the server-spec
    schema/Any schema/Any))

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
  {:middleware (-> execute-plan*
                   middleware/image-user-middleware
                   (middleware/execute-on-unflagged :bootstrapped))})

(def ^{:doc "Executes on bootstrapped nodes, with admin user credentials."}
  bootstrapped-meta
  {:middleware (-> execute-plan*
                   (middleware/execute-on-flagged :bootstrapped))})

(def ^{:doc "The bootstrap phase is executed with the image credentials, and
only not flagged with a :bootstrapped keyword."}
  default-phase-meta
  {:bootstrap {:middleware (->
                            execute-plan*
                            middleware/image-user-middleware
                            (middleware/execute-one-shot-flag :bootstrapped))}})


(defn phases-with-meta
  "Takes a `phases-map` and applies the default phase metadata and the
  `phases-meta` to the phases in it."
  [phases-map phases-meta default-phase-meta]
  (reduce-kv
   (fn [result k v]
     (let [dm (default-phase-meta k)
           pm (get phases-meta k)]
       (assoc result k (if (or dm pm)
                         ;; explicit overrides default
                         (vary-meta v #(merge dm % pm))
                         v))))
   nil
   (or phases-map {})))

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
  {:post [(validate ServerSpec %)]}
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

;;; In order to lookup spec data given just a basic target, we write
;;; the target maps to the targets extension in the session.

(def ^{:doc "Keyword for the targets extension"
       :private true}
  targets-extension :targets)

(defn ^:internal add-target
  "Record the target-map in the session targets extension."
  [session target]
  {:pre [(node/node? target)]}
  (update-extension session targets-extension (fnil conj []) target))

(defn ^:internal set-targets
  "Set the target-maps in the session targets extension."
  [session targets]
  {:pre [(every? node/node? targets)]}
  (set-extension session targets-extension targets))

(defn targets
  "Return the sequence of targets for the current operation.  The
  targets are recorded in the session targets extension."
  [session]
  (extension session targets-extension))

;;; # Map Nodes to Targets

;;; Given a sequence of nodes, we want to be able to return a sequence
;;; of target maps, where each target map is for a single node, and
;;; specifies the server-spec for that node.

(defn spec-for-target
  "Build a spec from a target and a sequence of predicate, spec pairs.
  The returned spec will contain all specs where the predicate
  returns true, merged in the order they are specified in the input
  sequence."
  [predicate-spec-pairs target]
  {:pre [(every? #(and (sequential? %)
                       (= 2 (count %))
                       (fn? (first %))
                       (map? (second %)))
                 predicate-spec-pairs)
         (validate [ExtendedServerSpec] (map second predicate-spec-pairs))]
   :post [(validate ExtendedServerSpec %)]}
  (reduce
   (fn [target-spec [predicate spec]]
     (if (predicate target)
       (merge-specs merge-spec-algorithm target-spec spec)
       target-spec))
   {}
   predicate-spec-pairs))

;;; Look up a phase plan-fn
(defn phase-plan
  "Return a plan-fn for `spec`, corresponding to the phase-spec map, `phase`."
  [spec {:keys [phase args]}]
  (if-let [f (phase (:phases spec))]
    (if args
      #(apply f % args)
      f)))

;;; # Phase call functions
(def PhaseWithArgs
  [(schema/one schema/Keyword "phase-kw") schema/Any])

(def PhaseCall
  (schema/either schema/Keyword IFn PhaseWithArgs))

(defn phase-args [phase-call]
  (if (keyword? phase-call)
    nil
    (rest phase-call)))

(defn phase-kw [phase-call]
  (if (keyword? phase-call)
    phase-call
    (first phase-call)))

(defn process-phase-calls
  "Process phases. Returns a phase list and a phase-map. Functions specified in
  `phases` are identified with a keyword and a map from keyword to function.
  The return vector contains a sequence of phase keywords and the map
  identifying the anonymous phases."
  [phase-calls]
  (let [phase-calls (if (or (keyword? phase-calls) (fn? phase-calls))
                      [phase-calls]
                      phase-calls)]
    (reduce
     (fn [[phase-kws phase-map] phase-call]
       (if (or (keyword? phase-call)
               (and (or (vector? phase-call) (seq? phase-call))
                    (keyword? (first phase-call))))
         [(conj phase-kws phase-call) phase-map]
         (let [phase-kw (-> (gensym "phase")
                            name keyword)]
           [(conj phase-kws phase-kw)
            (assoc phase-map phase-kw phase-call)])))
     [[] {}] phase-calls)))
