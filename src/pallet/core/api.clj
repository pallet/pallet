(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.algo.monads :refer [domonad m-map state-m with-monad]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :refer [destroy-node destroy-nodes-in-group nodes run-nodes]]
   [pallet.core.api-impl :refer :all]
   [pallet.core.session :refer [session session! with-session]]
   [pallet.core.user :refer [obfuscated-passwords]]
   [pallet.execute :refer [parse-shell-result]]
   [pallet.node :refer [id image-user primary-ip tag tag! taggable?]]
   [pallet.session.verify :refer [add-session-verification-key check-session]]
   [pallet.stevedore :refer [with-source-line-comments]]))

(let [v (atom nil)]
  (defn version
    "Returns the pallet version."
    []
    (or
     @v
     (reset! v (System/getProperty "pallet.version"))
     (reset! v (if-let [version (slurp (io/resource "pallet-version"))]
                       (string/trim version))))))

(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a sequence that contains a node-map for each
  matching node."
  [compute-service groups]
  (let [nodes (remove pallet.node/terminated? (nodes compute-service))]
    (tracef "service-state %s" (vec nodes))
    (filter identity (map (node->node-map groups) nodes))))

;;; # Execute action on a target node

(defn execute-action
  "Execute an action map within the context of the current session."
  [session action]
  (debugf "execute-action %s" (pr-str action))
  (let [executor (get-in session [::executor])
        execute-status-fn (get-in session [::execute-status-fn])
        _ (debugf "execute-action executor %s" (pr-str executor))
        _ (debugf "execute-action execute-status-fn %s" (pr-str execute-status-fn))
        _ (assert executor "No executor in session")
        _ (assert execute-status-fn "No execute-status-fn in session")
        [{:keys [out] :as rv} _ :as rrv] (executor session action)
        _ (debugf "execute-action rrv %s" (pr-str rrv))
        _ (assert (map? rv) (str "Action return value must be a map: " (pr-str rrv)))
        [rv session] (parse-shell-result session rv)]
    ;; TODO add retries, timeouts, etc
    (session!
     (update-in session [:results] (fnil conj []) rv))
    (execute-status-fn rv)
    rv))


;;; # Execute a phase on a target node
(defn stop-execution-on-error
  ":execute-status-fn algorithm to stop execution on an error"
  [result]
  (when (:error result)
    (debugf "Stopping execution %s" (:error result))
    (let [msg (-> result :error :message)]
      (throw (ex-info
              (str "Phase stopped on error" (if msg (str " - " msg)))
              {:error (:error result)
               :message msg}
              (-> result :error :exception))))))

(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

(defn- phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

(defn target-phase [target phase]
  (debugf "target-phase %s %s" target phase)
  (-> target :phases (get (phase-kw phase))))

(defn action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups. `target-map` is a map for the session
  describing the target."
  [service-state environment phase execution-settings-f target-kw target]
  {:pre [(map? target)
         (or (nil? environment) (map? environment))]}
  (let [plan-fn (target-phase target phase)
        args (phase-args phase)
        target-map {target-kw target}
        phase-kw (phase-kw phase)]
    (assert (and (not (map? plan-fn)) (fn? plan-fn))
            "plan-fn should be a function")
    (fn action-plan [plan-state]
      (let [{:keys [user executor execute-status-fn]}
            (execution-settings-f environment target-map)

            [rv session] (with-session
                           (add-session-verification-key
                            (merge {:user (:user environment)}
                                   target-map
                                   {:service-state service-state
                                    :plan-state plan-state
                                    :environment environment
                                    ::executor executor
                                    ::execute-status-fn execute-status-fn}))
                           [(apply plan-fn args) (session)])]
        {:result (:results session)
         :plan-state (:plan-state session)
         :target target
         :phase phase-kw
         :phase-args args}))))

(defmulti target-action-plan
  "Build action plans for the specified `phase` on all nodes or groups in the
  given `target`, within the context of the `service-state`. The `plan-state`
  contains all the settings, etc, for all groups."
  (fn target-action-plan
    [service-state plan-state environment phase execution-settings-f target]
    (tracef "target-action-plan %s" (:target-type target :node))
    (:target-type target :node)))

(defmethod target-action-plan :node
  [service-state plan-state environment phase execution-settings-f target]
  {:pre [target (:node target) phase]}
  (fn [plan-state]
    (logutils/with-context [:target (-> target :node primary-ip)]
      (with-script-for-node target plan-state
        ((action-plan
          service-state
          environment
          phase
          execution-settings-f
          :server target)
         plan-state)))))

(defmethod target-action-plan :group
  [service-state plan-state environment phase execution-settings-f group]
  {:pre [group]}
  (fn [plan-state]
    (logutils/with-context [:target (-> group :group-name)]
      ((action-plan
        service-state
        environment
        phase
        execution-settings-f
        :group group)
       plan-state))))

(defn execute-phase-on-target
  "Execute a phase on a single target."
  [service-state plan-state environment phase execution-settings-f target-map]
  (let [f (target-action-plan
           service-state plan-state environment phase execution-settings-f
           target-map)]
    (f plan-state)))



;; (defn execute-phase
;;   [service-state plan-state environment phase execution-settings-f targets]
;;   (let [targets-with-phase (filter #(target-phase % phase) targets)
;;         result-chans (doall
;;                       (for [target targets-with-phase]
;;                         (execute-phase-on-target
;;                          service-state plan-state environment phase
;;                          execution-settings-f target)))
;;         timeout (timeout (* 5 60 1000))] ; TODO make this configurable
;;     (tracef
;;      "action-plans: phase %s targets %s targets-with-phase %s"
;;      phase (vec targets) (vec targets-with-phase))
;;     (map #(alts!! % timeout) result-chans)))


;;; ## Action Plan Execution
(defn environment-execution-settings
  "Returns execution settings based purely on the environment"
  []
  (fn [environment _]
    (debugf "environment-execution-settings %s" environment)
    (debugf "Env user %s" (obfuscated-passwords (into {} (:user environment))))
    {:user (:user environment)
     :executor (get-in environment [:algorithms :executor])
     :execute-status-fn (get-in environment [:algorithms :execute-status-fn])}))

(defn environment-image-execution-settings
  "Returns execution settings based on the environment and the image user."
  []
  (fn [environment node]
    (let [user (into {} (filter val (image-user (:node node))))
          user (if (or (:private-key-path user) (:private-key user))
                 (assoc user :temp-key true)
                 user)]
      (debugf "Image-user is %s" (obfuscated-passwords user))
      {:user user
       :executor (get-in environment [:algorithms :executor])
       :execute-status-fn (get-in environment
                                  [:algorithms :execute-status-fn])})))


;;; ## Calculation of node count adjustments
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
  (let [existing-count (count targets)
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw
       (ex-info
        (format "Node :count not specified for group: %s" group)
        {:reason :target-count-not-specified
         :group group}) (:group-name group)))
    {:actual existing-count :target target-count
     :delta (- target-count existing-count)}))

(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  (into
   {}
   (map
    (juxt
     identity
     (fn [group]
       (group-delta (filter #(node-in-group? (:node %) group) targets) group)))
    groups)))

(defn groups-to-create
  "Return a sequence of groups that currently have no nodes, but will have nodes
  added."
  [group-deltas]
  (letfn [(new-group? [{:keys [actual target]}]
            (and (zero? actual) (pos? target)))]
    (->>
     group-deltas
     (filter #(new-group? (val %)))
     (map key)
     (map (fn [group-spec] (assoc group-spec :target-type :group))))))

(defn groups-to-remove
  "Return a sequence of groups that have nodes, but will have all nodes
  removed."
  [group-deltas]
  (letfn [(remove-group? [{:keys [actual target]}]
            (and (zero? target) (pos? actual)))]
    (->>
     group-deltas
     (filter #(remove-group? (second %)))
     (map #(assoc (first %) :target-type :group)))))

(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [targets group-deltas]
  (letfn [(pick-servers [[group {:keys [delta target]}]]
            (vector
             group
             {:nodes (take (- delta)
                           (filter #(node-in-group? (:node %) group) targets))
              :all (zero? target)}))]
    (into {}
          (->>
           group-deltas
           (filter #(when (neg? (:delta (val %))) %))
           (map pick-servers)))))

(defn nodes-to-add
  "Finds the specified number of nodes to be added to the given groups.
  Returns a map from group to a count of servers to add"
  [group-deltas]
  (into {}
        (->>
         group-deltas
         (filter #(when (pos? (:delta (val %))) [(key %) (:delta (val %))])))))

;;; ## Node creation and removal
(defn create-nodes
  "Create `count` nodes for a `group`."
  [compute-service environment group count]
  (map
   (fn [node] (assoc group :node node))
   (run-nodes
    compute-service group count
    (:user environment)
    nil
    (:provider-options environment nil))))

(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all]}]
  (debugf "remove-nodes")
  (if all
    (destroy-nodes-in-group compute-service (name (:group-name group)))
    (doseq [node nodes] (destroy-node compute-service (:node node)))))

;;; # Node state tagging

(def state-tag-name "pallet/state")

(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (read-string s)))

(defn set-state-for-node
  "Sets the boolean `state-name` flag on `node`."
  [state-name node]
  (debugf "set-state-for-node %s" state-name)
  (when (taggable? (:node node))
    (debugf "set-state-for-node taggable")
    (let [current (read-or-empty-map (tag (:node node) state-tag-name))
          val (assoc current (keyword (name state-name)) true)]
      (debugf "set-state-for-node %s %s" state-tag-name (pr-str val))
      (tag! (:node node) state-tag-name (pr-str val)))))

(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [state-name]
  (fn [node]
    (debugf "has-state-flag %s %s" state-name (id (:node node)))
    (let [v (get
             (read-or-empty-map (tag (:node node) state-tag-name))
             (keyword (name state-name)))]
      (tracef "has-state-flag %s" v)
      v)))

;;; # Exception reporting
(defn phase-errors-in-results
  "Return a sequence of phase errors for a sequence of result maps.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [results]
  (seq
   (concat
    (->>
     results
     (map #(update-in % [:result] (fn [r] (filter :error r))))
     (mapcat #(map (fn [r] (merge (dissoc % :result) r)) (:result %))))
    (filter :error results))))

(defn phase-errors
  "Return a sequence of phase errors for an operation.
   Each element in the sequence represents a failed action, and is a map,
   with :target, :error, :context and all the return value keys for the return
   value of the failed action."
  [result]
  (debugf "phase-errors %s" (vec (:results result)))
  (phase-errors-in-results (:results result)))

(defn phase-error-exceptions
  "Return a sequence of exceptions from phase errors for an operation. "
  [result]
  (->>  (phase-errors result)
        (map (comp :cause :error))
        (filter identity)))

(defn throw-phase-errors
  [result]
  (when-let [e (phase-errors result)]
    (throw
     (ex-info
      (str "Phase errors: " (string/join " " (map (comp :message :error) e)))
      {:errors e}
      (->> (map (comp :exeception :error) e)
           (filter identity)
           first)))))
