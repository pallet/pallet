(ns pallet.target-ops
  (:require
   [clojure.core.async :as async :refer [<! <!! >! chan]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.core.typed.async :refer [ReadOnlyPort WriteOnlyPort]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.compute :as compute]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.types :refer [ComputeService TargetMapSeq]]
   [pallet.crate.os :refer [os]]
   [pallet.exception :refer [combine-exceptions domain-error?]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.phase :as phase :refer [phases-with-meta]]
   [pallet.plan :as plan :refer [plan-fn]]
   [pallet.session :as session
    :refer [base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.spec :refer [bootstrapped-meta set-targets unbootstrapped-meta]]
   [pallet.target :as target]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils.async :refer [concat-chans go-try map-thread reduce-results]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

;;; # Execute Plan Functions on Mulitple Targets

;; (defmulti target-id-map
;;   "Return a map with the identity information for a target.  The
;;   identity information is used to identify phase result maps."
;;   :target-type)

;; (defmethod target-id-map :node [target]
;;   (select-keys target [:node]))

(def target-result-map
  (-> plan/plan-result-map
      (dissoc :action-results)
      (assoc (optional-key :action-results) [plan/action-result-map]
             :target {schema/Keyword schema/Any})))

(defn execute-target-plan
  "Using the session, execute plan-fn on target."
  [session
   {:keys [node phases target-type] :or {target-type :node} :as target}
   plan-fn]
  {:pre [(or node (= target-type :group))]
   :post [(validate target-result-map %)]}
  (assoc (middleware/execute session target plan-fn plan/execute)
    :target target))

(defn ^:internal execute-plan-fns*
  "Using the executor in `session`, execute phase on all targets.
  The targets are executed in parallel, each in its own thread.  A
  single [result, exception] tuple will be written to the channel ch,
  where result is a sequence of results for each target, and exception
  is a composite exception of any thrown exceptions.

  Does not call phase middleware.  Does call plan middleware."
  [session target-plans ch]
  (let [c (chan)]
    (->
     (map-thread (fn execute-plan-fns [[target plan-fn]]
                   (try
                     [(execute-target-plan session target plan-fn)]
                     (catch Exception e
                       (let [data (ex-data e)]
                         (if (contains? data :action-results)
                           [data e]
                           [nil e])))))
                 target-plans)
     (concat-chans c))
    (reduce-results c ch)))

(defn execute-plan-fns
  "Execute plan functions on targets.  Write a result tuple to the
  channel, ch.  Targets are grouped by phase-middleware, and phase
  middleware is called.  plans are executed in parallel.
  `target-plans` is a sequence of target, plan-fn tuples."
  ([session target-plans ch]
     (debugf "execute-plan-fns %s target-plans" (count target-plans))
     (let [mw-targets (group-by (comp :phase-middleware meta second)
                                target-plans)
           c (chan)]
       (concat-chans
        (for [[mw target-plans] mw-targets]
          (if mw
            (mw session target-plans ch)
            (execute-plan-fns* session target-plans ch)))
        c)
       (reduce-results c ch)))
  ([session target-plans]
     (let [c (chan)]
       (execute-plan-fns session target-plans c)
       (let [[results e] (<!! c)]
         (if (or (nil? e) (domain-error? e))
           results
           (throw (ex-info "execute-plan-fns failed" {:results results} e)))))))

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
            res (->> results (mapv #(assoc % :phase phase-name)))]
        (>! ch [res exception])))))

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
                      errs (plan/errors results)
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

(ann destroy-targets
  [ComputeService TargetMapSeq ReadOnlyPort -> WriteOnlyPort])
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
  [session compute-service spec user count base-name ch]
  (debugf "create-targets %s %s" count (:group-name spec))
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
