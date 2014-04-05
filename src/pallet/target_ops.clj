(ns pallet.target-ops
  (:require
   [clojure.core.async :as async :refer [<! <!! >! put! chan close!]]
   [clojure.set :as set]
   [taoensso.timbre :as logging :refer [debugf tracef]]
   [pallet.compute :as compute :refer [node-spec-schema]]
   [pallet.core.api-builder :refer [defn-api defn-sig]]
   [pallet.core.context :refer [with-context]]
   [pallet.exception :refer [combine-exceptions domain-error?]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.node :as node]
   [pallet.phase :as phase :refer [phase-schema phases-with-meta PhaseSpec]]
   [pallet.plan :as plan
    :refer [execute-plans errors plan-fn PlanResult Target TargetPlan]]
   [pallet.session :as session
    :refer [BaseSession base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.spec
    :refer [bootstrapped-meta extend-specs phase-plan phases-schema
            set-targets spec-for-target server-spec-schema
            unbootstrapped-meta ExtendedServerSpec]]
   [pallet.utils.async
    :refer [go-try map-chan reduce-results ReadPort WritePort]]
   [pallet.utils.rex-map :refer [merge-rex-maps]]
   [schema.core :as schema :refer [check optional-key validate]]))

;;; TODO explicit middleware for tagging via plan-state
;;; TODO move target-ops into spec?

(def TargetSpec
  "A target combined with a spec"
  {:target {schema/Any schema/Any}
   :spec ExtendedServerSpec})

(def TargetPhase
  "A sequence of target plans, with an id for identifying the results."
  {:result-id {schema/Any schema/Any}
   :target-plans [TargetPlan]})

(def PhaseResult
  "A phase result is the result of some set of operations."
  {(optional-key :exception) Throwable
   (optional-key :results) [PlanResult]
   (optional-key :new-targets) [Target]
   (optional-key :old-targets) [Target]
   (optional-key :state) {schema/Keyword schema/Any}})

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
  {:target-plans (->>
                  target-specs
                  (map #(target-plan % phase-spec))
                  (remove nil?))
   :result-id phase-spec})

;;; # Running Phases

(defn-api series-phases
  "Executes a sequence of phase steps.

  A phase is a set of operations that are carried with a
  synchronisation point after them.

  Each step is a a function.  At each step, the function is called
  with the current result map and a result channel.  The function must
  write a single rex-map to the result channel.  The map may
  contain :exception, :results, :new-targets, and :old-targets keys.

  The results of each step are combined and written to the output
  channel, ch, as a rex-map.

  `init` is an initial result map."
  {:sig [[[clojure.lang.IFn] (schema/maybe PhaseResult) WritePort :- ReadPort]]}
  [fs init ch]
  (go-try ch
    (let [c (chan)]
      (loop [fs fs
             res (or init {})]
        (debugf "series-phases step (%s steps)" (count fs))
        (debugf "series-phases %s results" (count (:results res)))
        (debugf "series-phases %s errors" (count (errors (:results res))))
        (debugf "series-phases exception %s" (boolean (:exception res)))
        (if-let [f (first fs)]
          (do
            (f res c)
            (if-let [result (<! c)]
              (recur (rest fs) (merge-rex-maps res result))
              (>! ch res)))
          (>! ch res))))))

(defn-api parallel-phases
  "Call functions in parallel, returning a combined result.  Each
  function must take a single channel argument, to which it writes a
  single rex-map.

  `init` is an initial result map."
  {:sig [[[clojure.lang.IFn] (schema/maybe PhaseResult) WritePort :- ReadPort]]}
  [fs init ch]
  (debugf "parallel-phases (%s phases)" (count fs))
  (go-try ch
    (let [c (chan)]
      (doseq [f fs] (f init c))
      (reduce-results (async/take (count fs) c) ch))))

;;; ## Targets State

;;; These functions use a map for containing :targets and
;;; :failed-targets keys.

(def TargetsState
  {(optional-key :targets) (schema/maybe #{Target})
   (optional-key :failed-targets) (schema/maybe #{Target})})

(defn partition-result-targets
  "Partition targets based on results.  Return a tuple with a set of
  good targets and a set of failed targets."
  [results]
  (debugf "partition-result-targets %s" (pr-str results))
  (let [errs (plan/errors results)
        err-targets (set (map :target errs))
        all-targets (set (map :target results))]
    [(set/difference all-targets err-targets) err-targets]))

(defn targets-state
  "Return a state that is the set of successful targets."
  [{:keys [results new-targets old-targets]}]
  {:pre []
   :post [(or (validate TargetsState %) true)]}
  (let [[good bad] (partition-result-targets results)]
    {:targets (set/union
               (set/difference (set bad) (set old-targets))
               good
               (set new-targets))
     :failed-targets bad}))

;;; # Phases

(defn-api execute-target-phase
  "Execute plans, merging the result-id map into the result of each
  plan-fn.  Write a result-exception map to the channel, ch."
  {:sig [[BaseSession TargetPhase WritePort :- ReadPort]]}
  [session {:keys [result-id target-plans] :as target-phase} ch]
  (with-context {:result-id result-id}
    (logging/debugf "execute-target-phase %s target plans" (count target-plans))
    (let [c (chan)]
      (execute-plans session target-plans c)
      (go-try ch
        (if-let [{:keys [results exception] :as r} (<! c)]
          (let [r (update-in r [:results]
                             (fn [results]
                               (mapv #(merge result-id %) results)))
                e (combine-exceptions
                   [exception] "execute-target-phase failed" r)]
            (>! ch (cond-> r e (assoc :exception e))))
          (>! ch {:exception (ex-info "No result from execute-plans" {})}))))))

(defn-sig remove-failed-targets
  "Remove target plans from `target-phase` if they are for one of `targets`."
  {:sig [[TargetPhase (schema/maybe #{Target}) :- TargetPhase]]}
  [target-phase targets]
  (update-in target-phase [:target-plans]
             #(remove (comp (or targets #{}) :target) %)))

(defn lift-phase
  "Return a function that will execute the target-phase when called
  with results and a channel argument."
  [session target-phase]
  (fn lift [results c]
    (execute-target-phase session target-phase c)))

(defn-api lift-when-no-errors-phase
  "Return a function that will execute the target-phase if there
  are no errors in previous results."
  {:sig [[BaseSession TargetPhase :- schema/Any]]}
  [session target-phase]
  (fn lift [{:keys [results exception]} c]
    (if (or (errors results) exception)
      (put! c {})
      (execute-target-phase session target-phase c))))

(defn lift-unfailed-targets-phase
  "Return a phase function that calls execute-target-phase on target-phase,
  for just those targets that have no errors."
  [session target-phase]
  (debugf "lift-unfailed-targets-phase %s target-plans (unfiltered)"
              (count (:target-plans target-phase)))
  (fn lift-unfailed-targets [results c]
    (let [{:keys [failed-targets]} (targets-state results)
          target-phase (remove-failed-targets target-phase failed-targets)]
      (debugf "lift-unfailed-targets-phase %s target-plans (filtered)"
              (count (:target-plans target-phase)))
      (execute-target-phase session target-phase c))))

(defn create-nodes-phase
  [compute-service node-spec user count base-name]
  {:pre [(validate node-spec-schema node-spec)]}
  (fn create-nodes [_ c]
    (compute/create-nodes compute-service node-spec user count base-name c)))

(defn destroy-nodes-phase
  "Return a phase function that calls destroy-nodes on the non-failed
  targets in results."
  [compute-service targets]
  (fn destroy-nodes-with-filter [results c]
    (let [{:keys [failed-targets]} (targets-state results)
          remove-targets (set/difference targets failed-targets)]
      (debugf "destroy-nodes-with-filter %s targets of %s"
              (count remove-targets) (count targets))
      (compute/destroy-nodes compute-service remove-targets c))))
