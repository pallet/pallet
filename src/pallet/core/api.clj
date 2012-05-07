(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [clojure.algo.monads :only [domonad m-map state-m with-monad]]
   [clojure.string :only [blank?]]
   [pallet.action-plan :only [execute stop-execution-on-error translate]]
   [pallet.compute :only [destroy-nodes-in-group destroy-node nodes run-nodes]]
   [pallet.environment :only [get-for]]
   [pallet.executors :only [default-executor]]
   [pallet.node :only [image-user tag tag!]]
   [pallet.session.action-plan
    :only [assoc-action-plan get-session-action-plan]]
   [pallet.session.verify :only [add-session-verification-key check-session]]
   [pallet.utils :only [*admin-user*]]
   pallet.core.api-impl
   [slingshot.slingshot :only [throw+]]))

(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a map that contains all the nodes, nodes for each
  group, and groups for each node.

  Also the service environment."
  [compute-service groups]
  (let [nodes (remove pallet.node/terminated? (nodes compute-service))]
    {:node->groups (into {} (map (node->groups groups) nodes))
     :group->nodes (into {} (map (group->nodes nodes) groups))}))

(defn service-state-with-nodes
  "Add the specified nodes to the service-state. `new-nodes` must be a map from
  a group to a sequence of new nodes in that group."
  [service-state new-nodes]
  (-> service-state
      (update-in [:group->nodes] (partial merge-with concat) new-nodes)
      (update-in [:node->groups]
                 merge (into {}
                             (mapcat
                              (fn [[g ns]]
                                (map vector ns (repeat [g])))
                              new-nodes)))))

(defn service-state-without-nodes
  "Add the specified nodes to the service-state. `new-nodes` must be a map from
  a group to a sequence of new nodes in that group."
  [service-state old-nodes]
  (-> service-state
      (update-in [:group->nodes]
                 (partial merge-with #(remove (set (:nodes %2)) %1)) old-nodes)
      (update-in [:node->groups]
                 #(into {}
                        (remove
                         (comp
                          (set (mapcat (comp :nodes val) old-nodes))
                          key)
                         %)))))

(defn filtered-service-state
  "Applies a filter to the nodes in a service-state."
  [service-state node-predicate]
  (-> service-state
      (update-in
       [:group->nodes] (fn [m]
                         (into {}
                               (map
                                #(vector
                                  (first %)
                                  (remove
                                   (complement node-predicate) (second %)))
                                m))))
      (update-in
       [:node->groups] (fn [m]
                         (into {}
                               (remove
                                (comp (complement node-predicate) first)
                                m))))))

;;; ## Action Plan Building
(defn action-plan
  "Build the action plan for the specified `plan-fn` on the given `node`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups. `target-map` is a map for the session
  describing the target."
  [service-state environment plan-fn target-map]
  {:pre [(not (map? plan-fn)) (fn? plan-fn)
         (map? target-map)
         (or (nil? environment) (map? environment))]}
  (fn action-plan [plan-state]
    (logging/tracef "action-plan plan-state %s" plan-state)
    (let [session (add-session-verification-key
                   (merge
                    {:user (:user environment *admin-user*)
                     ;; :environment environment
                     }
                    target-map
                    {:service-state service-state
                     :plan-state plan-state}))
          [rv session] (plan-fn session)
          _ (check-session session '(plan-fn session))
          [action-plan session] (get-session-action-plan session)
          [action-plan session] (translate action-plan session)]
      [action-plan (:plan-state session)])))

;;; ### Action plans for a group
(defmulti action-plans
  "Build action plans for the specified `phase` on all nodes or groups in the
  given `target`, within the context of the `service-state`. The `plan-state`
  contains all the settings, etc, for all groups."
  (fn [service-state environment phase target-type target] target-type))

(defn- action-plans-for-nodes
  [service-state environment phase group nodes]
  (if-let [plan-fn (-> group :phases phase)]
    (with-monad state-m
      (domonad
       [action-plans (m-map
                      (fn [node]
                        {:pre [node]}
                        (fn [plan-state]
                          (with-script-for-node node
                            ((action-plan
                              service-state environment plan-fn
                              {:server {:node node}
                               :group group})
                             plan-state))))
                      nodes)]
       (map
        #(hash-map :target %1 :target-type :node :phase phase :action-plan %2)
        nodes action-plans)))
    (fn [plan-state] [nil plan-state])))

;; Build action plans for the specified `phase` on all nodes in the given
;; `group`, within the context of the `service-state`. The `plan-state` contains
;; all the settings, etc, for all groups.
(defmethod action-plans :group-nodes
  [service-state environment phase target-type group]
  (let [group-nodes (-> service-state :group->nodes (get group))]
    (action-plans-for-nodes service-state environment phase group group-nodes)))

;; Build action plans for the specified `phase` on all nodes in the given
;; `group`, within the context of the `service-state`. The `plan-state` contains
;; all the settings, etc, for all groups.
(defmethod action-plans :group-node-list
  [service-state environment phase target-type [group nodes]]
  (action-plans-for-nodes service-state environment phase group nodes))

;; Build an action plan for the specified `phase` on the given `group`, within
;; the context of the `service-state`. The `plan-state` contains all the
;; settings, etc, for all groups.
(defmethod action-plans :group
  [service-state environment phase target-type group]
  (if-let [plan-fn (-> group :phases phase)]
    (with-monad state-m
      (domonad
       [plan (action-plan service-state environment plan-fn {:group group})]
       [{:action-plan plan
         :target group
         :target-type :group
         :phase phase}]))
    (fn [plan-state] [nil plan-state])))

;;; ### Action plans for phases
(defn action-plans-for-phase
  "Build action plans for the specified `phase` on the given `groups`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups.

  The exact format of `groups` depends on `target-type`.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list.

  Returns a sequence of maps, where each map has :phase, :action-plan :target
  and :target-type keys"
  [service-state environment target-type targets phase]
  (with-monad state-m
    (domonad
     [action-plans (m-map
                    (partial
                     action-plans
                     service-state environment phase target-type)
                    targets)]
     (apply concat action-plans))))

(defn action-plans-for-phases
  "Build action plans for the specified `phase` on the given `groups`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups."
  [target-type service-state environment groups phases]
  (logging/tracef
   "groups %s phases %s" (vec (map :group-name groups)) (vec phases))
  (with-monad state-m
    (domonad
     [action-plans (m-map
                    (partial
                     action-plans-for-phase
                     target-type service-state environment groups)
                    phases)]
     action-plans)))

;;; ## Action Plan Execution
(defn environment-execution-settings
  "Returns execution settings based purely on the environment"
  [environment]
  (fn [_]
    {:user (:user environment pallet.utils/*admin-user*)
     :executor (get-in environment [:algorithms :executor] default-executor)
     :executor-status-fn (get-in environment [:algorithms :execute-status-fn]
                                 #'stop-execution-on-error)}))

(defn environment-image-execution-settings
  "Returns execution settings based on the environment and the image user."
  [environment]
  (fn [node]
    {:user (or (image-user node) (:user environment pallet.utils/*admin-user*))
     :executor (get-in environment [:algorithms :executor] default-executor)
     :executor-status-fn (get-in environment [:algorithms :execute-status-fn]
                                 #'stop-execution-on-error)}))


(defn execute-action-plan*
  "Execute the `action-plan` on the `target`."
  [session executor execute-status-fn
   {:keys [action-plan phase target-type target]}]
  (logging/tracef "execute-action-plan*")
  (let [[result session] (execute
                          action-plan session executor execute-status-fn)]
    {:target target
     :target-type target-type
     :plan-state (:plan-state session)
     :result result
     :phase phase
     :errors (seq (remove (complement :error) result))}))

(defmulti execute-action-plan
  "Execute the `action-plan` on the `target`."
  (fn [service-state plan-state environment user executor execute-status-fn
       {:keys [action-plan phase target-type target]}]
    target-type))

(defmethod execute-action-plan :node
  [service-state plan-state environment user executor execute-status-fn
   {:keys [action-plan phase target-type target] :as action-plan-map}]
  (logging/tracef "execute-action-plan :node")
  (with-script-for-node target
    (execute-action-plan*
     {:server {:node target}
      :service-state service-state
      :plan-state plan-state
      :user user
      :environment environment}
     executor execute-status-fn action-plan-map)))

(defmethod execute-action-plan :group
  [service-state plan-state environment user executor execute-status-fn
   {:keys [action-plan phase target-type target] :as action-plan-map}]
  (logging/tracef "execute-action-plan :group")
  (execute-action-plan*
   {:group target
    :service-state service-state
    :plan-state plan-state
    :user user
    :environment environment}
   executor execute-status-fn action-plan-map))

;;; ## Calculation of node count adjustments
(defn group-delta
  "Calculate actual and required counts for a group"
  [service-state group]
  (let [existing-count (count (-> service-state :group->nodes (get group)))
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw+
       {:reason :target-count-not-specified
        :group group}
       "Node :count not specified for group: %s" (:group-name group)))
    {:actual existing-count :target target-count
     :delta (- target-count existing-count)}))

(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [service-state groups]
  (into
   {}
   (map
    (juxt identity (partial group-delta service-state))
    groups)))

(defn groups-to-create
  "Return a sequence of groups that currently have no nodes, but will have nodes
  added."
  [group-deltas]
  (letfn [(new-group? [{:keys [actual target]}]
            (and (zero? actual) (pos? target)))]
    (filter #(when (new-group? (second %)) (first %)) group-deltas)))

(defn groups-to-remove
  "Return a sequence of groups that will have nodes, but will have all nodes
  removed."
  [group-deltas]
  (letfn [(remove-group? [{:keys [actual target]}]
            (and (zero? target) (pos? actual)))]
    (filter #(when (remove-group? (second %)) (first %)) group-deltas)))

(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [service-state group-deltas]
  (letfn [(pick-servers [[group {:keys [delta target]}]]
            (vector
             group
             {:nodes (take (- delta)
                           (-> service-state :group->nodes (get group)))
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
  (run-nodes
   compute-service group count
   (get-for environment [:user] *admin-user*)
   nil
   (get-for environment [:provider-options] nil)))

(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all]}]
  (logging/infof "remove-nodes")
  (if all
    (destroy-nodes-in-group compute-service (name (:group-name group)))
    (doseq [node nodes] (destroy-node compute-service node))))

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
  (let [current (read-or-empty-map (tag node state-tag-name))
        val (assoc current (keyword (name state-name)) true)]
    (tag! node state-tag-name (pr-str val))))

(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [state-name]
  (fn [node]
    (get
     (read-or-empty-map (tag node state-tag-name))
     (keyword (name state-name)))))
