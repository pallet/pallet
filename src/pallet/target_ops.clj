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
   [pallet.map-merge :refer [merge-keys]]
   [pallet.middleware :as middleware]
   [pallet.node :as node :refer [node?]]
   [pallet.phase :as phase :refer [phases-with-meta]]
   [pallet.plan :as api :refer [execute plan-fn]]
   [pallet.session :as session
    :refer [base-session? extension plan-state set-extension target
            target-session? update-extension]]
   [pallet.spec :refer [bootstrapped-meta set-targets unbootstrapped-meta]]
   [pallet.target :as target]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils :refer [combine-exceptions maybe-update-in total-order-merge]]
   [pallet.utils.async :refer [concat-chans go-try map-thread reduce-results]]))

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
