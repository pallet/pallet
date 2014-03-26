(ns pallet.target-ops
  (:require
   [clojure.core.async :as async :refer [<! <!! >! chan]]
   [taoensso.timbre :as logging :refer [debugf tracef]]
   [pallet.compute :as compute]
   [pallet.crate.node-info :as node-info]
   [pallet.exception :refer [combine-exceptions domain-error?]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.phase :as phase :refer [phases-with-meta]]
   [pallet.plan :as plan :refer [execute-plan-fns errors plan-fn]]
   [pallet.session :as session
    :refer [base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.spec
    :refer [bootstrapped-meta extend-specs set-targets unbootstrapped-meta]]
   [pallet.target :as target]
   [pallet.utils.async :refer [go-try]]
   [schema.core :as schema :refer [check optional-key validate]]))

;;; TODO explicit plan-state override of node data
;;; TODO explicit middleware for tagging via plan-state
;;; TODO move target-ops into spec?


;;; # Lift
(defn lift-phase
  "Execute phase on each of targets, write a result tuple to the
  channel, ch."
  [session phase targets consider-targets ch]
  {:pre [(every? (some-fn target/node #(= :group (:target-type %))) targets)]}
  (let [target-plans (map
                      (juxt identity #(phase/target-phase (:phases %) phase))
                      targets)]
    (go-try ch
      (let [session (set-targets ;; TODO move this higher
                     session
                     (filter target/node (concat targets consider-targets)))
            c (chan)
            _ (execute-plan-fns session target-plans c)
            [results exception] (<! c)
            phase-name (phase/phase-kw phase)
            results (->> results (mapv #(assoc % :phase phase-name)))]
        (>! ch [results
                (combine-exceptions
                 [exception] "lift-phase failed" {:results results})])))))

(defn partition-targets
  "Partition targets based on results.  Return a tuple with a sequence
  of good targets and a sequence of bad targets."
  [results targets]
  (let [errs (plan/errors results)
        err-targets (set (map :target errs))
        target-groups (group-by (comp boolean err-targets) targets)]
    [(get target-groups false) (get target-groups true)]))


(defn lift-phases
  "Using `session`, execute `phases` on `targets`, while considering
  `consider-targets`.  Returns a channel containing a tuple of a
  sequence of the results and a sequence of any exceptions thrown.
  Will try and call all phases, on all targets.  Any error will halt
  processing for the target on which the error occurs.
  Execution is synchronised across all targets on each phase."
  [session phases targets consider-targets ch]
  (logging/debugf "lift-phases :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
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
                      errs (plan/errors results)
                      res (concat res results)
                      ptargets (first (partition-targets results ptargets))]
                  (recur
                   (rest phases)
                   res
                   (concat
                    es [exception
                        (if (seq errs)
                          (ex-info "Phase failed on target" {:errors errs}))])
                   ptargets)))
              [res (combine-exceptions es)]))))))

(defn lift-op
  "Using `session`, execute `phases` on `targets`, while considering
  `consider-targets`.  Returns a channel containing a tuple of a
  sequence of the results and any exception thrown.
  Each phase is a synchronisation point, and an error on any target will
  stop the processing of further phases on all targets."
  [session phases targets consider-targets ch]
  (logging/debugf "lift-op :phases %s :targets %s" (vec phases) (count targets))
  (go-try ch
    (>! ch
        (loop [phases phases
               res []]
          (if-let [phase (first phases)]
            (let [c (chan)
                  _ (lift-phase session phase targets consider-targets c)
                  [results exception] (<! c)
                  res (concat res results)]
              (logging/debugf "lift-op phase %s" phase)
              (logging/debugf "lift-op exception %s" exception)
              (logging/debugf "lift-op errors %s" (errors results))
              (if (or (errors results) exception)
                [res (combine-exceptions
                      [exception] "lift-op failed" {:results res})]
                (recur (rest phases) res)))
            [res nil])))))

;;; # Creating and Removing Nodes

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
            errs (plan/errors res)
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
  [session compute-service node-spec spec user count base-name ch]
  (debugf "create-targets %s %s" count (:group-name spec))
  (tracef "create-targets node-spec %s" node-spec)
  (go-try ch
    (let [c (chan)]
      (compute/create-nodes compute-service node-spec user count base-name c)
      (let [[nodes e] (<! c)
            targets (map
                     (fn [node] (assoc spec :node node))
                     nodes)]
        (lift-phase session :pallet/os targets nil c)
        (let [[info-results info-e :as info] (<! c)
              [targets err-targets] (partition-targets info-results targets)
              infos (into {} (map (juxt (comp target/id :target) :return-value)
                                  (filter :return-value info-results)))
              targets (map (fn [target]
                             (if-let [info (get infos (target/id target))]
                               (update-in target [:node] merge info)
                               target))
                           targets)]
          (tracef "info-results %s" (pr-str info-results))
          (tracef "infos %s" (pr-str infos))
          (tracef "new targets %s" (pr-str targets))
          (if info-e
            (>! ch [{:targets targets
                     :results info-results}
                    (ex-info "create-targets failed" {} info-e)])
            (let [targets (map #(extend-specs % [(node-info/server-spec {})])
                               targets)]
              (lift-phases
               session [:settings :bootstrap]
               targets
               nil c)
              (let [[results e1] (<! c)]
                (>! ch [{:targets targets
                         :results (concat info-results results)}
                        (combine-exceptions [e info-e e1])])))))))))
