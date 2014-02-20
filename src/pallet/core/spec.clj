(ns pallet.core.spec
  "Provides server-spec functionality.

A server-spec can specify phases that provide facets of a particular
service."
  (:require
   [clojure.core.async :as async :refer [<! <!! >! chan]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.async :refer [concat-chans go-try map-thread reduce-results]]
   [pallet.compute :as compute]
   [pallet.contracts :refer [check-server-spec]]
   [pallet.core.api :as api :refer [execute plan-fn]]
   [pallet.core.middleware :as middleware]
   [pallet.core.phase :as phase :refer [phases-with-meta]]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.session :as session
    :refer [base-session? extension set-extension target
            target-session? update-extension]]
   [pallet.core.target :as target]
   [pallet.crate.os :refer [os]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :as node :refer [node?]]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils
    :refer [combine-exceptions maybe-update-in total-order-merge]]))

;;; # Domain Model

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
     [KeyAlgorithms GroupSpec GroupSpec -> GroupSpec])
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

(ann default-phases [ServerSpecSeq -> (Seqable Keword)])
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
     [(Nilable (NonEmptySeqable '[[Node -> Boolean] ServerSpec])) Node
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
  [(Nilable (NonEmptySeqable '[[Node -> Boolean] ServerSpec]))
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

;;; # Lift
(defmulti target-id-map
  "Return a map with the identity information for a target.  The
  identity information is used to identify phase result maps."
  :target-type)

(defmethod target-id-map :node [target]
  (select-keys target [:node]))

(defn execute-target-phase
  "Using the session, execute phase on a single target."
  [session phase {:keys [node phases target-type]
                  :or {target-type :node} :as target}]
  {:pre [(or node (= target-type :group))]}
  ((fnil merge {})
   (phase/execute-phase session target phases phase execute)
   (target-id-map (assoc target :target-type target-type))
   {:phase phase}))

(defn execute-phase
  "Using the executor in `session`, execute phase on all targets.
  The targets are executed in parallel, each in its own thread.  A
  single [result, exception] tuple will be written to the channel ch,
  where result is a sequence of results for each target, and exception
  is a composite exception of any thrown exceptions."
  [session phase targets ch]
  (let [c (chan)]
    (->
     (map-thread #(execute-target-phase session phase %) targets)
     (concat-chans c))
    (reduce-results c ch)))

(defn execute-phase-with-middleware
  "Execute phase on all targets, using phase middleware.  Put a result
  tuple onto the channel, ch."
  [session phase targets ch]
  (debugf "lift-phase-with-middleware %s on %s targets" phase (count targets))
  (let [mw-targets (group-by (comp :phase-middleware meta) targets)
        c (chan)]
    (concat-chans
     (for [[mw targets] mw-targets]
       (if mw
         (mw session phase targets ch)
         (execute-phase session phase targets ch)))
     c)
    (reduce-results c ch)))

(defn lift-phase
  "Execute phase on each of targets, write a result tuple to the
  channel, ch."
  [session phase targets consider-targets ch]
  {:pre [(every? (some-fn target/node #(= :group (:target-type %))) targets)]}
  (go-try ch
    (let [session (set-targets
                   session
                   (filter target/node (concat targets consider-targets)))
          c (chan)
          _ (execute-phase-with-middleware session phase targets c)
          [results exception] (<! c)
          phase-name (phase/phase-kw phase)
          res (->> results (mapv #(assoc % :phase phase-name)))]
      (>! ch [res exception]))))

(defn lift-phases
  "Using `session`, execute `phases` on `targets`, while considering
  `consider-targets`.  Returns a channel containing a tuple of a
  sequence of the results and a sequence of any exceptions thrown.
  Will try and call all phases, on all targets.  Any error will halt
  processing for the target on which the error occurs."
  [session phases targets consider-targets ch]
  (logging/debugf "lift-phases :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
  ;; TODO support post-phase, partitioning middleware, etc
  (go-try ch
    (>! ch
        (let [c (chan)]
          (loop [phases phases
                 res []
                 es []
                 ptargets targets]
            (if-let [phase (first phases)]
              (do
                (lift-phase session phase targets consider-targets c)
                (let [[results exception] (<! c)
                      errs (api/errors results)
                      err-nodes (set (map :node errs))
                      res (concat res results)
                      ptargets (remove (comp err-nodes :node) targets)]
                  (recur
                   (rest phases)
                   res
                   (concat
                    es [exception
                        (if (seq errs)
                          (ex-info "Phase failed on node" {:errors errs}))])
                   ptargets)))
              [res (combine-exceptions es)]))))))

(defn lift-op*
  "Using `session`, execute `phases` on `targets`, while considering
  `consider-targets`.  Returns a channel containing a tuple of a
  sequence of the results and a sequence of any exceptions thrown.
  Each phase is a synchronisation point, and an error on any node will
  stop the processing of further phases."
  [session phases targets consider-targets ch]
  (logging/debugf "lift-op* :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
  ;; TODO support post-phase, partitioning middleware, etc
  (go-try ch
    (>! ch
        (loop [phases phases
               res []]
          (if-let [phase (first phases)]
            (let [c (chan)
                  _ (lift-phase session phase targets consider-targets c)
                  [results exception] (<! c)
                  res (concat res results)]
              (if (or (some #(some :error (:action-results %)) results)
                      exception)
                [res exception]
                (recur (rest phases) res)))
            [res nil])))))

(defn lift-op
  "Execute phases on targets.  Returns a sequence of results."
  [session phases targets consider-targets]
  (logging/debugf "lift-op :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
  ;; TODO - use exec-operation
  (let [c (chan)
        _ (lift-op* session phases targets consider-targets c)
        [results exceptions] (<!! c)]
    (when (seq exceptions)
      (throw (ex-info "Exception in phase"
                      {:results results
                       :execptions exceptions}
                      (first exceptions))))
    results))

;;; # Plan Functions

(defn os-detection-phases
  "Return a phase map with pallet os-detection phases."
  []
  ;; TODO add middleware guard to only run if no info in plan-state
  {:pallet/os (vary-meta
               (plan-fn [session] (os session))
               merge unbootstrapped-meta)
   :pallet/os-bs (vary-meta
                  (plan-fn [session] (os session))
                  merge bootstrapped-meta)})

;;; # Creating and Removing Nodes

(ann destroy-targets [ComputeService TargetMapSeq Channel -> Channel])
(defn destroy-targets
  "Run the targets' :destroy-server phase, then destroy the nodes in
  `targets` nodes.  If the destroy-server phase fails, then the
  corresponding node is not removed.  The result of the phase and the
  result of the node destruction are written to the :destroy-server
  and :old-node-ids keys of a map in a rex-tuple to the output chan,
  ch."
  [session compute-service targets ch]
  (debugf "destroy-targets %s targets" (count targets))
  (go-try ch
    (let [c (chan)]
      (lift-phase session :destroy-server targets nil c)
      (let [[res e] (<! c)
            errs (api/errors res)
            error-nodes (set (map :node errs))
            nodes-to-destroy (->> targets (map :node) (remove error-nodes))]
        (compute/destroy-nodes compute-service nodes-to-destroy c)
        (let [[ids de] (<! c)]
          (debugf "destroy-targets old-ids %s" (vec ids))
          (>! ch [{:destroy-server res :old-node-ids ids}
                  (combine-exceptions
                   [e
                    (if (seq errs)
                      (ex-info "destroy-targets failed" {:errs errs}))
                    de])]))))))

;;; This tries to run bootstrap, so if tagging is not supported,
;;; bootstrap is at least attempted.
(defn create-targets
  "Using `session` and `compute-service`, create nodes using the
  `:node-spec` in `spec`, possibly authorising `user`.  Creates
  `count` nodes, each named distinctly based on `base-name`.  Runs the
  bootstrap phase on new targets.  A rex-tuple is written to ch with a
  map, with :targets and :results keys."
  [session compute-service spec user count base-name ch]
  (debugf "create-targets %s" count)
  (debugf "create-targets node-spec %s" (:node-spec spec))
  (go-try ch
    (let [c (chan)]
      (compute/create-nodes
       compute-service (:node-spec spec) user count base-name c)
      (let [[nodes e] (<! c)
            targets (map
                     (fn [node] (assoc spec :node node))
                     nodes)]
        (debugf "create-targets %s %s" (vec targets) e)
        (lift-phases
         session [:pallet/os :settings :bootstrap]
         (map #(update-in % [:phases] merge (os-detection-phases)) targets)
         nil c)
        (let [[results e1] (<! c)]
          (debugf "create-targets results %s" (pr-str [results e1]))
          (>! ch [{:targets targets :results results}
                  (combine-exceptions [e e1])]))))))


        ;; (lift-phase session :pallet/os
        ;;             (map #(merge (os-detection-phases) %) targets) nil c)
        ;; (debugf "create-targets :pallet/os running")
        ;; (let [[results1 e1] (<! c)
        ;;       errs1 (api/errors results1)
        ;;       result {:targets targets :pallet/os results1}
        ;;       err-nodes (set (map :node errs1))
        ;;       b-targets (if-not e1 (remove (comp err-nodes :node) targets))]
        ;;   (debugf "create-targets :pallet-os %s %s" e1 (vec errs1))


        ;;   ;; NEED TO RUN :settings

        ;;   ;; Need a low-level multi-phase function that progresses
        ;;   ;; each node as far as possible (:pallet/os, :settings,
        ;;   ;; :bootstrap).

        ;;   ;; For this, also need exceptions to appear in :errors so
        ;;   ;; that nodes can be properly filtered.

        ;;   (lift-phase session :bootstrap b-targets nil c)
        ;;   (debugf "create-targets :bootstrap running")
        ;;   (let [[results2 e2] (<! c)
        ;;         errs2 (api/errors results2)]
        ;;     (debugf "create-targets :bootstrap results %s"
        ;;             (pr-str [results2 e2]))
        ;;     (>! ch [{:targets targets :pallet/os results1 :bootstrap results2}
        ;;             (combine-exceptions
        ;;              [e e1 e2
        ;;               (if errs1
        ;;                 (ex-info "pallet/os failed" {:errors errs1}))
        ;;               (if errs2
        ;;                 (ex-info "bootstrap failed" {:errors errs2}))])])))


(defn os-map
  [session]
  {:pre [(target-session? session)]}
  (plan-state/get-settings
   (:plan-state session) (target/id (target session)) :pallet/os {}))

(ann os-family [Session -> Keyword])
(defn os-family
  "OS-Family of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (:os-family (os-map session)))

(ann os-version [Session -> String])
(defn os-version
  "OS-Family of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (:os-version (os-map session)))

;; (ann packager [Session -> Keyword])
;; (defn packager
;;   []
;;   ;; (or
;;   ;;  (:packager (os-map session))
;;   ;;  (packager-for-os (os-family session) (os-version session)))

;;   ;; TODO fix
;;   :apt
;;   )


(ann admin-group [Session -> String])
(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group
   (os-family session)
   (os-version session)))
