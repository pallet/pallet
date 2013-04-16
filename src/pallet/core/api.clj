(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string])
  (:use
   [clojure.algo.monads :only [domonad m-map state-m with-monad]]
   [clojure.tools.logging :only [debugf tracef]]
   [clojure.string :only [blank?]]
   [pallet.action-plan :only [execute stop-execution-on-error translate]]
   [pallet.algo.fsmop :only [wait-for]]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :only [destroy-nodes-in-group destroy-node nodes run-nodes]]
   [pallet.core.session :only [session with-session]]
   [pallet.environment :only [get-for]]
   [pallet.executors :only [default-executor]]
   [pallet.node :only [id image-user primary-ip tag tag! taggable?]]
   [pallet.session.action-plan
    :only [assoc-action-plan get-session-action-plan]]
   [pallet.session.verify :only [add-session-verification-key check-session]]
   [pallet.utils :only [maybe-assoc]]
   pallet.core.api-impl
   [pallet.core.user :only [*admin-user*]]))

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

;;; ## Action Plan Building
(defn action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups. `target-map` is a map for the session
  describing the target."
  [service-state environment plan-fn args target-map]
  {:pre [(not (map? plan-fn)) (fn? plan-fn)
         (map? target-map)
         (or (nil? environment) (map? environment))]}
  (fn action-plan [plan-state]
    (tracef "action-plan plan-state %s" plan-state)
    (let [s (with-session
              (add-session-verification-key
               (merge
                {:user (:user environment *admin-user*)}
                target-map
                {:service-state service-state
                 :plan-state plan-state
                 :environment environment}))
              (apply plan-fn args)
              (check-session (session) '(plan-fn))
              (session))]
      (let [[action-plan session] (get-session-action-plan s)
            [action-plan session] (translate action-plan session)]
        [action-plan (:plan-state session)]))))

(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

(defn- phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

(defn target-phase [target phase]
  (-> target :phases (get (phase-kw phase))))

(defmulti target-action-plan
  "Build action plans for the specified `phase` on all nodes or groups in the
  given `target`, within the context of the `service-state`. The `plan-state`
  contains all the settings, etc, for all groups."
  (fn [service-state environment phase target]
    (tracef "target-action-plan %s" (:target-type target :node))
    (:target-type target :node)))

(defmethod target-action-plan :node
  [service-state environment phase target]
  {:pre [target]}
  (fn [plan-state]
    (logutils/with-context [:target (-> target :node primary-ip)]
      (with-script-for-node target
        ((action-plan
          service-state environment
          (target-phase target phase) (phase-args phase)
          {:server target})
         plan-state)))))

(defmethod target-action-plan :group
  [service-state environment phase group]
  {:pre [group]}
  (fn [plan-state]
    (logutils/with-context [:target (-> group :group-name)]
      ((action-plan
        service-state environment
        (target-phase group phase) (phase-args phase)
        {:group group})
       plan-state))))

(defn action-plans
  [service-state environment phase targets]
  (let [targets-with-phase (filter #(target-phase % phase) targets)]
    (tracef
     "action-plans: phase %s targets %s targets-with-phase %s"
     phase (vec targets) (vec targets-with-phase))
    (with-monad state-m
      (domonad
       [action-plans
        (m-map
         (partial target-action-plan service-state environment phase)
         targets-with-phase)]
       (map
        #(hash-map :target %1 :phase (phase-kw phase) :action-plan %2)
        targets-with-phase action-plans)))))


;;; ## Action Plan Execution
(defn environment-execution-settings
  "Returns execution settings based purely on the environment"
  [environment]
  (fn [_]
    {:user (:user environment *admin-user*)
     :executor (get-in environment [:algorithms :executor] default-executor)
     :executor-status-fn (get-in environment [:algorithms :execute-status-fn]
                                 #'stop-execution-on-error)}))

(defn environment-image-execution-settings
  "Returns execution settings based on the environment and the image user."
  [environment]
  (fn [node]
    (let [user (into {} (filter val (image-user (:node node))))
          user (if (or (:private-key-path user) (:private-key user))
                 (assoc user :temp-key true)
                 user)]
      (debugf "Image-user is %s" user)
      {:user user
       :executor (get-in environment [:algorithms :executor] default-executor)
       :executor-status-fn (get-in environment [:algorithms :execute-status-fn]
                                   #'stop-execution-on-error)})))


(defn execute-action-plan*
  "Execute the `action-plan` on the `target`."
  [session executor execute-status-fn
   {:keys [action-plan phase target-type target]}]
  (tracef "execute-action-plan*")
  (with-session session
    (let [[result session] (execute
                            action-plan session executor execute-status-fn)
          errors (seq (remove (complement :error) result))
          value {:target target
                 :target-type target-type
                 :plan-state (:plan-state session)
                 :result result
                 :phase (phase-kw phase)}]
      (maybe-assoc value :errors errors))))

(defmulti execute-action-plan
  "Execute the `action-plan` on the `target`."
  (fn [service-state plan-state environment user executor execute-status-fn
       {:keys [action-plan phase target]}]
    (:target-type target :node)))

(defmethod execute-action-plan :node
  [service-state plan-state environment user executor execute-status-fn
   {:keys [action-plan phase target-type target] :as action-plan-map}]
  (tracef "execute-action-plan :node")
  (logutils/with-context [:target (-> target :node primary-ip)]
    (with-script-for-node target
      (execute-action-plan*
       {:server target
        :service-state service-state
        :plan-state plan-state
        :user user
        :environment environment}
       executor execute-status-fn action-plan-map))))

(defmethod execute-action-plan :group
  [service-state plan-state environment user executor execute-status-fn
   {:keys [action-plan phase target-type target] :as action-plan-map}]
  (tracef "execute-action-plan :group")
  (logutils/with-context [:target (-> target :group-name)]
    (execute-action-plan*
     {:group target
      :service-state service-state
      :plan-state plan-state
      :user user
      :environment environment}
     executor execute-status-fn action-plan-map)))

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
    (get-for environment [:user] *admin-user*)
    nil
    (get-for environment [:provider-options] nil))))

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
    (get
     (read-or-empty-map (tag (:node node) state-tag-name))
     (keyword (name state-name)))))

;;; # Exception reporting
(defn throw-operation-exception
  "If the operation has a logged exception, throw it. This will block on the
   operation being complete or failed."
  [operation]
  (when-let [e (:exception @operation)]
    (throw e)))

(defn phase-errors
  "Return the phase errors for an operation"
  [operation]
  (when (:phase-errors (wait-for operation))
    (mapcat :errors (:results (wait-for operation)))))

(defn throw-phase-errors
  [operation]
  (when-let [e (phase-errors operation)]
    (throw
     (ex-info
      (str "Phase errors: " (string/join " " (map (comp :message :error) e)))
      {:errors e}))))
