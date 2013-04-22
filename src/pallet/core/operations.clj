(ns pallet.core.operations
  "Built in operations"
  (:refer-clojure :exclude [delay])
  (:require
   [pallet.core.primitives :as primitives]
   [pallet.core.api :as api]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.environment :only [environment]]
   [pallet.algo.fsmop :only [delay-for dofsm reduce* result succeed]]
   [pallet.utils :only [apply-map]]))

(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them."
  [compute-service groups service-state plan-state environment targets
   execution-settings-f]
  {:pre [compute-service]}
  (dofsm node-count-adjuster
    [group-deltas         (result (api/group-deltas targets groups))
     nodes-to-remove      (result (api/nodes-to-remove targets group-deltas))
     nodes-to-add         (result (api/nodes-to-add group-deltas))
     [results1 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            :destroy-server
                            (mapcat :nodes (vals nodes-to-remove))
                            execution-settings-f)
     _ (primitives/remove-group-nodes compute-service nodes-to-remove)
     [results2 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            :destroy-group
                            (api/groups-to-remove group-deltas)
                            execution-settings-f)
     [results3 plan-state] (primitives/build-and-execute-phase
                            targets plan-state environment
                            :create-group
                            (api/groups-to-create group-deltas)
                            execution-settings-f)
     new-nodes (primitives/create-group-nodes
                compute-service environment nodes-to-add)]
    {:new-nodes new-nodes
     :old-nodes (mapcat :nodes (vals nodes-to-remove))
     :targets (->> targets
                   (concat new-nodes)
                   (remove (set (mapcat :nodes (vals nodes-to-remove)))))
     :plan-state plan-state
     :service-state (->> service-state
                         (concat new-nodes)
                         (remove (set (mapcat :nodes (vals nodes-to-remove)))))
     :results (concat results1 results2 results3)}))

;;; ## Top level operations
(defn group-nodes
  [compute groups]
  (dofsm group-nodes
    [service-state (primitives/service-state compute groups)]
    service-state))

(def ^{:doc "A sequence of keywords, listing the lift-options"}
  lift-options
  [:targets :phase-execution-f :execution-settings-f
   :post-phase-f :post-phase-fsm :partition-f])

(defn lift
  "Lift nodes (`targets` which defaults to `service-state`), given a
`plan-state`, `environment` and the `phases` to apply.

## Options

`:targets`
: used to restrict the nodes on which the phases are run to a subset of
  `service-state`.  Defaults to `service-state`.

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys."
  [service-state plan-state environment phases
   {:keys [targets execution-settings-f phase-execution-f
           post-phase-f post-phase-fsm]
    :or {targets service-state
         phase-execution-f primitives/build-and-execute-phase
         execution-settings-f (api/environment-execution-settings)}}]
  (logging/debugf
   "lift :phases %s :targets %s" (vec phases) (vec (map :group-name targets)))
  (letfn [(phase-meta [phase target]
            (-> (api/target-phase target phase) meta))]
    (dofsm lift
      [[results plan-state] (reduce*
                             (fn reducer [[results plan-state] phase]
                               (dofsm reduce-phases
                                 [meta (result
                                       (phase-meta phase (first targets)))
                                 f (result (or (:phase-execution-f meta)
                                               phase-execution-f))
                                  [r ps] (f
                                          service-state plan-state environment
                                          phase targets
                                          (or
                                           (:execution-settings-f meta)
                                           execution-settings-f))
                                  results1 (result (concat results r))
                                  _ (result
                                     (when post-phase-f
                                       (post-phase-f targets phase r)))
                                  _ (result
                                     (doseq [f (->>
                                                targets
                                                (map
                                                 (comp
                                                  :post-phase-f
                                                  #(phase-meta phase %)))
                                                (remove nil?)
                                                distinct)]
                                       (f targets phase r)))
                                  _ (if post-phase-fsm
                                      (post-phase-fsm targets phase r)
                                      (result nil))
                                  _ (reduce* (fn post-reducer [_ f]
                                               (f targets phase r))
                                             nil
                                             (->>
                                              targets
                                              (map
                                               (comp :post-phase-fsm
                                                     #(phase-meta phase %)))
                                              (remove nil?)
                                              distinct))
                                  _ (succeed
                                     (not (some :errors r))
                                     (merge
                                      {:phase-errors true
                                       :phase phase
                                       :results results1}
                                      (when-let [e (some
                                                    #(some
                                                      (comp :cause :error)
                                                      (:errors %))
                                                    results1)]
                                        (logging/errorf
                                         e "Phase Error in %s" phase)
                                        {:exception e})))]
                                 [results1 ps]))
                             [[] plan-state]
                             phases)]
      {:results results
       :targets targets
       :plan-state plan-state})))

(defn delay
  "Returns a delay fsm.

## Options

`:units`
: the units for the delay. A keyword from, :ns, :us, :ms, :s, :mins, :hours"
  ([delay units]
     (delay-for delay units))
  ([delay]
     (delay-for delay :ms)))

(defn ^:internal partition-targets
  "Partition targets using the, possibly nil, default partitioning function f.

There are three sources of partitioning applied.  The default passed to the
function, a paritioning based on the partitioning and post-phase functions in
the target's metadata, and the target's partitioning function from the metadata.

The partitioning by metadata is applied so that post-phase functions are applied
to the correct targets in lift."
  [targets phase f]
  (let [fns (comp
             (juxt :partition-f :post-phase-f :post-phase-fsm)
             meta #(api/target-phase % phase))]
    (->>
     targets
     (clojure.core/partition-by fns)
     (mapcat
      #(let [[pf & _] (fns (first %))]
         (if pf
           (pf %)
           [%]))))))

(defn lift-partitions
  "Lift targets by phase, applying partitions for each phase.

To apply phases at finer than a group granularity (so for example, a
`:post-phase-f` function is applied to nodes rather than a whole group), we can
use partitioning.

The partitioning function takes a sequence of targets, and returns a sequence of
sequences of targets.  The function can filter targets as required.

For example, this can be used to implement a rolling restart, or a blue/green
deploy.

## Options

Options are as for `lift`, with the addition of:

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

Other options as taken by `lift`."
  [service-state plan-state environment phases
   {:keys [targets partition-f]
    :or {targets service-state}
    :as options}]
  (logging/debugf
   "lift-partitions :phases %s :targets %s"
   (vec phases) (vec (map :group-name targets)))
  (dofsm lift-phases
    [[results plan-state]
     (reduce*
      (fn phase-reducer [[results plan-state] phase]
        (dofsm lift-partitions
          [[results plan-state]
           (reduce*
            (fn target-reducer [[r plan-state] targets]
              (dofsm reduce-phases
                [{:keys [results plan-state]}
                 (lift
                  service-state plan-state environment [phase]
                  (assoc options :targets targets))]
                [(concat r results) plan-state]))
            [results plan-state]
            (let [fns (comp
                       (juxt :partition-f :post-phase-f :post-phase-fsm
                             :phase-execution-f)
                       meta #(api/target-phase % phase))]
              (partition-targets targets phase partition-f)))]
          [results plan-state]))
      [[] plan-state]
      phases)]
    {:results results
     :targets targets
     :plan-state plan-state}))

(defn converge
  "Converge the `groups`, using the specified service-state to provide the
existing nodes.  The `:bootstrap` phase is run on new nodes.  When tagging is
supported the `:bootstrap` phase is run on those nodes without a :bootstrapped
flag.

## Options

`:targets`
: used to restrict the nodes on which the phases are run to a subset of
  `service-state`.  Defaults to `service-state`."
  [compute groups service-state plan-state environment phases
   {:keys [targets execution-settings-f]
    :or {targets service-state
         execution-settings-f (api/environment-execution-settings)}
    :as options}]
  (logging/debugf
   "converge :phase %s :groups %s :settings-groups %s"
   (vec phases)
   (vec (map :group-name groups))
   (vec (map :group-name targets)))
  (dofsm converge
    [{:keys [new-nodes old-nodes targets service-state plan-state results]
      :as result}
     (node-count-adjuster
      compute groups service-state plan-state environment targets
      execution-settings-f)]
    result))
