(ns pallet.target-ops
  (:require
   [clojure.core.async :as async :refer [<! <!! >! chan]]
   [clojure.set :as set]
   [taoensso.timbre :as logging :refer [debugf tracef]]
   [pallet.compute :as compute]
   [pallet.exception :refer [combine-exceptions domain-error?]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.node :as node]
   [pallet.phase :as phase :refer [phase-schema phases-with-meta PhaseSpec]]
   [pallet.plan :as plan
    :refer [execute-plans errors plan-fn TargetPlan]]
   [pallet.session :as session
    :refer [BaseSession base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.spec
    :refer [bootstrapped-meta extend-specs phase-plan phases-schema
            set-targets spec-for-target server-spec-schema
            unbootstrapped-meta ExtendedServerSpec]]
   [pallet.utils.async :refer [go-try map-chan ReadPort WritePort]]
   [schema.core :as schema :refer [check optional-key validate]]))

;;; TODO explicit middleware for tagging via plan-state
;;; TODO move target-ops into spec?

(def TargetSpec {:target {schema/Any schema/Any}
                 :spec ExtendedServerSpec})

(def TargetPhase {:result-id {schema/Any schema/Any}
                  :target-plans [TargetPlan]})

;;; # Matching Nodes
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
                 predicate-spec-pairs)
         (validate [ExtendedServerSpec] (map second predicate-spec-pairs))]}
  {:target target
   :spec (spec-for-target predicate-spec-pairs target)})

;;; # Execution Targets
(defn target-plan
  "Return a target plan map"
  [{:keys [target spec] :as target-spec} phase-spec]
  {:pre [(validate TargetSpec target-spec)
         (validate PhaseSpec phase-spec)]
   :post [(or (nil? %) (validate TargetPlan %))]}
  (if-let [f (phase-plan spec phase-spec)]
    {:target target
     :plan-fn f}))

(defn target-phase
  "Return a target phase map"
  [target-specs phase-spec]
  {:pre [(validate [TargetSpec] target-specs)
         (validate PhaseSpec phase-spec)]
   :post [(validate TargetPhase %)]}
  {:target-plans (map #(target-plan % phase-spec) target-specs)
   :result-id phase-spec})

;;; # Lift
(defn lift-phase
  "Execute plans, merging the result-id map into the result of each
  plan-fn.  Write a result tuple to the channel, ch."
  [session {:keys [result-id target-plans] :as target-phase} ch]
  {:pre [(validate BaseSession session)
         (validate TargetPhase target-phase)
         (validate WritePort ch)]
   :post [(validate ReadPort %)]}
  (logging/debugf "lift-phase %s %s" result-id (count target-plans))
  (let [c (chan)]
    (execute-plans session target-plans c)
    (go-try ch
      (let [[results exception] (<! c)
            results (mapv #(merge result-id %) results)]
        (>! ch [results
                ;; wrap any exception, so we get results with phase info
                (combine-exceptions
                 [exception] "lift-phase failed" {:results results})])))))

;;; # Synchronisation
(defn synch-phases
  "Executes a sequence of steps, with possible early termination.
  Each step is a map with an :op function, and possibly :state-update
  :flow, and :op-queue functions.

  At each step, the op function is called with the current state and a
  result channel.  The op function must write a single rex-tuple of
  PlanResults to the result channel.

  If specified, the :state-update function is then called with the
  rex-tuple written by :op, and the current state, and the result is
  assigned to the state.

  If specified, the :flow function is then called with the rex-tuple
  written by :op, the current state and the pending steps. The
  function returns a new sequence of pending steps.

  The results of each step are combined and written to the output
  channel, ch, as a rex-tuple."
  [steps initial-state ch]
  {:pre [(validate
          [{:op schema/Any
            (schema/optional-key :state-update) schema/Any
            (schema/optional-key :flow) schema/Any}]
          steps)
         (validate WritePort ch)]
   :post [(validate ReadPort %)]}
  (go-try ch
    (>! ch
        (let [c (chan)]
          (loop [steps steps
                 state initial-state
                 res []
                 es []]
            (debugf "synch-phases step (%s steps)" (count steps))
            (debugf "synch-phases state %s" state)
            (debugf "synch-phases %s results" (count res))
            (debugf "synch-phases %s exceptions" (count es))
            (if-let [{:keys [op state-update flow]} (first steps)]
              (do
                (assert op "Invalid step (no :op)")
                (op state c)
                (debugf "synch-phases called :op")
                (let [[results exception :as r] (<! c)
                      _ (assert
                         (sequential? results)
                         "Invalid step :op function (result must be sequential)")
                      _ (debugf "synch-phases result %s" r)
                      res (concat res results)
                      state (if state-update
                              (state-update r state)
                              state)
                      steps-queue (if flow
                                    (flow r state (rest steps))
                                    (rest steps))]
                  (debugf "synch-phases recurring (%s steps remaining)"
                          (count steps-queue))
                  (recur steps-queue
                         state
                         res
                         (if exception (conj es exception) es))))
              [res (combine-exceptions es)]))))))

(defn break-on-error
  "A flow function for sync-phases that will terminate further steps
  on error or exception."
  [[results exception] state steps]
  (if-not (or (errors results) exception)
    steps))

(defn partition-result-targets
  "Partition targets based on results.  Return a tuple with a set of
  good targets and a set of failed targets."
  [results]
  (debugf "partition-result-targets %s" (pr-str results))
  (let [errs (plan/errors results)
        err-targets (set (map :target errs))
        all-targets (set (map :target results))]
    [(set/difference all-targets err-targets) err-targets]))

(defn successful-targets-state
  "Return a state that is the set of successful targets."
  [[results] state]
  (first (partition-result-targets results)))

(defn filter-target-phase
  "Remove targets from a target-phase."
  [target-phase targets]
  {:pre [(set? targets)
         (validate TargetPhase target-phase)]
   :post [(validate TargetPhase %)]}
  (update-in target-phase [:target-plans] #(filter (comp targets :target) %)))

(defn synch-phases-abort-on-error
  "Run phases of functions, fs, aborting further phases if any phase
  reports an error.  Each function must accept a result channel as its
  only argument, and write a single rex-tuple as a result to it.  The
  rex-tuple result must be a sequence of maps, that use the :error key
  to signal a domain error."
  [fs ch]
  {:pre [(validate [schema/Any] fs)
         (validate WritePort ch)]
   :post [(validate ReadPort %)]}
  (logging/debugf "synch-phases-abort-on-error: %s phases" (count fs))
  (synch-phases
   (map #(hash-map :op (fn [state ch] (% ch)) :flow break-on-error) fs)
   nil
   ch))

(defn synch-phases-filter-on-error
  "Run phases of functions, fs, aborting further phases for any single
  node that reports an error.  Each function must accept a set of good
  targets, and a result channel, and write a single rex-tuple as a
  result to it.  The rex-tuple result must be a sequence of maps, that
  use the :error key to signal a domain error, and :target to record
  the target of the operation."
  [fs init-state ch]
  (logging/debugf "synch-phases-filter-on-error %s functions" (count fs))
  (synch-phases
   (map #(hash-map :op % :state-update successful-targets-state) fs)
   init-state
   ch))

;;; # Multi-phase lift

;;; ## lift-phase adaptors for use with synch-phases- functions

(defn lift-target-phase-fn
  "Return a function that will lift the target-phases when called
  with a result channel argument."
  [session target-phase]
  (fn lift-target-phase [c]
    (lift-phase session target-phase c)))

(defn lift-target-phase-with-filter-fn
  "Return a function that calls lift-phase on target-phase, filtering targets
  specified in the target-set.  For use with synch-phases-filter-on-error."
  [session target-phase]
  (fn lift-target-phase-with-filter [target-set c]
    (lift-phase session (filter-target-phase target-phase target-set) c)))

(defn destroy-nodes-with-filter-fn
  [compute-service]
  (fn destroy-nodes-with-filter [targets c]
    (compute/destroy-nodes
     compute-service
     targets
     c)))

;;; ## Lift multiple phases
(defn lift-abort-on-error
  "Using `session`, execute `target-phases`.  Returns a channel
  containing a tuple of a sequence of the results and any exception
  thrown.  Each target phase is a synchronisation point, and an error
  on any target will stop the processing of further phases on all
  targets."
  [session target-phases ch]
  {:pre [(validate BaseSession session)
         (validate [TargetPhase] target-phases)
         (validate WritePort ch)]
   :post [(validate ReadPort %)]}
  (logging/debugf "lift-op target-ids %s" (mapv :target-id target-phases))
  (synch-phases-abort-on-error
   (map #(lift-target-phase-fn session %) target-phases)
   ch))

(defn lift-filter-on-error
  "Using `session`, execute `phases` on `targets`.  Returns a channel
  containing a tuple of a sequence of the results and a sequence of
  any exceptions thrown.  Will try and call all phases, on all
  targets.  Any error will halt processing for the target on which the
  error occurs.  Execution is synchronised across all targets on each
  phase."  [session target-phases ch]
  (logging/debugf "lift-phases target-ids %s" (mapv :target-id target-phases))
  (synch-phases-filter-on-error
   (map #(lift-target-phase-with-filter-fn session %) target-phases)
   (map :target (:target-plans (first target-phases)))
   ch))

;;; # Creating and Removing Nodes
(defn destroy-targets
  "Run the target-plan, then destroys the target nodes.
  If a plan fails, then the corresponding node is not removed.  The
  result of the phase and the result of the node destruction are
  written to the :results keys of a map in a rex-tuple to the output
  chan, ch."
  [session compute-service target-phase ch]
  (debugf "destroy-targets %s targets" (count (:target-plans target-phase)))
  (synch-phases-filter-on-error
   [(lift-target-phase-with-filter-fn session target-phase)
    (destroy-nodes-with-filter-fn compute-service)]
   (map :target (:target-plans target-phase))
   ch))

;;; This tries to run an initial phase, so if tagging is not
;;; supported, bootstrap can at least be attempted.
(defn create-targets
  "Using `session` and `compute-service`, create nodes using the
  `:node-spec`, possibly authorising `user`.  Creates `count` nodes,
  each named distinctly based on `base-name`.  Runs the settings-fn
  and plan-fn on new targets.  A rex-tuple is written to ch with a
  map, with :targets and :results keys."
  [session compute-service node-spec user count base-name
   settings-fn plan-fn ch]
  (debugf "create-targets %s nodes with base-name %s" count base-name)
  (tracef "create-targets node-spec %s" node-spec)
  (synch-phases
   [{:op (fn create-target-op [_ c]
           (debugf "create-targets calling compute service")
           (compute/create-nodes
            compute-service node-spec user count base-name c)
           (debugf "create-targets calling compute service done"))
     :state-update successful-targets-state
     :flow (fn create-targets-flow [results targets _]
             [{:op
               (lift-target-phase-with-filter-fn
                session
                {:result-id {:phase :settings}
                 :target-plans (if settings-fn
                                 (map #(hash-map :target % :plan-fn settings-fn)
                                      targets))})}
              {:op
               (lift-target-phase-with-filter-fn
                session
                {:result-id {:phase :create-targets}
                 :target-plans (if plan-fn
                                 (map #(hash-map :target % :plan-fn plan-fn)
                                      targets))})}])}]
   nil
   ch))
