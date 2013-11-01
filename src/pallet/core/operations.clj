(ns pallet.core.operations
  "Built in operations"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.core.api :as api]
   [pallet.core.primitives :as primitives :refer [status!]]
   [pallet.node :refer [node-map]]))

(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them."
  [operation
   compute-service groups service-state plan-state environment targets
   execution-settings-f]
  {:pre [compute-service]}
  (let [group-deltas (api/group-deltas targets groups)
        nodes-to-remove (api/nodes-to-remove targets group-deltas)
        nodes-to-add (api/nodes-to-add group-deltas)
        old-nodes (->>
                   (vals nodes-to-remove)
                   (mapcat :nodes)
                   (map (comp node-map :node)))
        [results1 plan-state] (primitives/execute-phase
                               targets plan-state environment
                               :destroy-server
                               (mapcat :nodes (vals nodes-to-remove))
                               execution-settings-f)
        _ (status! operation :node-count-adjuster/destroy-server-phase-run)
        _ (primitives/remove-group-nodes compute-service nodes-to-remove)
        _ (status! operation :node-count-adjuster/nodes-removed)
        [results2 plan-state] (primitives/execute-phase
                               targets plan-state environment
                               :destroy-group
                               (api/groups-to-remove group-deltas)
                               execution-settings-f)
        _ (status! operation :node-count-adjuster/destroy-group-run)
        [results3 plan-state] (primitives/execute-phase
                               targets plan-state environment
                               :create-group
                               (api/groups-to-create group-deltas)
                               execution-settings-f)
        _ (status! operation :node-count-adjuster/create-group-run)
        new-nodes (primitives/create-group-nodes
                   compute-service environment nodes-to-add)
        _ (status! operation :node-count-adjuster/nodes-created)]
    {:new-nodes new-nodes
     :old-nodes old-nodes
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
  [operation compute groups]
  (let [service-state (api/service-state compute groups)]
    (status! operation :pallet.operations/group-nodes-run)
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
  to `pallet.core.primitives/execute-phase`.

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
  [operation service-state plan-state environment phases
   {:keys [targets execution-settings-f phase-execution-f
           post-phase-f post-phase-fsm]
    :or {targets service-state
         phase-execution-f #'primitives/execute-phase
         execution-settings-f (api/environment-execution-settings)}}]
  {:pre [(:user environment)]}
  (logging/debugf
   "lift :phases %s :targets %s" (vec phases) (vec (map :group-name targets)))
  (logging/tracef "lift environment %s" environment)
  (letfn [(phase-meta [phase target]
            (-> (api/target-phase target phase) meta))]
    (let [[results plan-state]
          (reduce
           (fn reducer [[results plan-state] phase]
             (if (::stop-lift plan-state)
               [results plan-state]
               (let [meta (phase-meta phase (first targets))
                     f  (or (:phase-execution-f meta)
                            phase-execution-f)
                     _  (logging/debugf
                         "phase-execution-f %s" f)
                     [r ps] (f
                             service-state plan-state
                             environment phase targets
                             (or
                              (:execution-settings-f meta)
                              execution-settings-f))
                     _ (logging/debugf "result %s %s" (vec r) ps)
                     results1 (vec (concat results r))
                     _ (when post-phase-f
                         (post-phase-f targets phase r))
                     _ (doseq [f (->>
                                  targets
                                  (map
                                   (comp
                                    :post-phase-f
                                    #(phase-meta phase %)))
                                  (remove nil?)
                                  distinct)]
                         (f targets phase r))
                     _ (when post-phase-fsm
                         (post-phase-fsm targets phase r))
                     _ (reduce
                        (fn post-reducer [_ f]
                          (f targets phase r))
                        nil
                        (->>
                         targets
                         (map
                          (comp :post-phase-fsm
                                #(phase-meta phase %)))
                         (remove nil?)
                         distinct))
                     ps (if (some :error r)
                          (do
                            (logging/debugf "stop-lift detected")
                            (assoc ps ::stop-lift true))
                          ps)]
                 ;; (when (some :error (mapcat :result r))
                 ;;   (logging/debugf
                 ;;    "Errors %s"
                 ;;    (some :error (mapcat :result r)))
                 ;;   (throw (ex-info
                 ;;           "Errors"
                 ;;           {:type :pallet/phase-errors})))
                 [results1 ps])))
           [[] plan-state]
           phases)]
      (do
        (logging/tracef "lift (count results) %s" (count results))
        {:results results
         :targets targets
         :plan-state plan-state}))))

;; (defn delay
;;   "Returns a delay fsm.

;; ## Options

;; `:units`
;; : the units for the delay. A keyword from, :ns, :us, :ms, :s, :mins, :hours"
;;   ([delay units]
;;      (delay-for delay units))
;;   ([delay]
;;      (delay-for delay :ms)))

(defn ^:internal partition-targets
  "Partition targets using the, possibly nil, default partitioning function f.

There are three sources of partitioning applied.  The default passed to the
function, a partioning based on the partitioning and post-phase functions in
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
  [operation service-state plan-state environment phases
   {:keys [targets partition-f]
    :or {targets service-state}
    :as options}]
  {:pre [(:user environment)]}
  (logging/debugf
   "lift-partitions :phases %s :targets %s"
   (vec phases) (vec (map :group-name targets)))
  (let [[outer-results plan-state]
        (reduce
         (fn phase-reducer [[acc-results plan-state] phase]
           (let [[lift-results plan-state]
                 (reduce
                  (fn target-reducer [[r plan-state] targets]
                    (let
                        [{:keys [results plan-state]}
                         (lift
                          operation
                          service-state plan-state environment [phase]
                          (assoc options :targets targets))]
                      (do
                        (logging/tracef "back from lift")
                        (logging/tracef
                         "lift-partitions (count r) %s (count results) %s"
                         (count r) (count results))
                        [(concat r results) plan-state])))
                  [acc-results plan-state]
                  (let [fns (comp
                             (juxt :partition-f :post-phase-f :post-phase-fsm
                                   :phase-execution-f)
                             meta #(api/target-phase % phase))]
                    (partition-targets targets phase partition-f)))]
             (do
               (logging/tracef "back from phase loop")
               (logging/tracef "(count lift-results) %s" (count lift-results))
               [lift-results plan-state])))
         [[] plan-state]
         phases)]
    (do
      (logging/tracef "back from partitions")
      (logging/tracef "(count outer-results) %s" (count outer-results))
      {:results outer-results
       :targets targets
       :plan-state plan-state})))

(defn converge
  "Converge the `groups`, using the specified service-state to provide the
existing nodes.  The `:bootstrap` phase is run on new nodes.  When tagging is
supported the `:bootstrap` phase is run on those nodes without a :bootstrapped
flag.

## Options

`:targets`
: used to restrict the nodes on which the phases are run to a subset of
  `service-state`.  Defaults to `service-state`."
  [operation compute groups service-state plan-state environment phases
   {:keys [targets execution-settings-f]
    :or {targets service-state
         execution-settings-f (api/environment-execution-settings)}
    :as options}]
  {:pre [(:user environment)]}
  (logging/debugf
   "converge :phase %s :groups %s :settings-groups %s"
   (vec phases)
   (vec (map :group-name groups))
   (vec (map :group-name targets)))
  (let [{:keys [new-nodes old-nodes targets service-state plan-state results]
         :as result}
        (node-count-adjuster
         operation
         compute groups service-state plan-state environment targets
         execution-settings-f)]
    result))
