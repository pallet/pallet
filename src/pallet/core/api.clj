(ns pallet.core.api
  "Base level API for pallet"
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [clojure.algo.monads :only [domonad m-map state-m with-monad]]
   [pallet.action-plan :only [execute stop-execution-on-error translate]]
   [pallet.compute :only [destroy-nodes-in-group destroy-node nodes run-nodes]]
   [pallet.core :only [default-executor]]
   [pallet.environment :only [get-for]]
   [pallet.node :only [image-user]]
   [pallet.session.action-plan
    :only [assoc-action-plan get-session-action-plan]]
   [pallet.session.verify :only [add-session-verification-key]]
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
  settings, etc, for all groups."
  [service-state environment plan-fn target]
  (fn action-plan [plan-state]
    (with-script-for-node (-> target :server :node)
      (let [session (add-session-verification-key
                     (merge
                      target
                      {:service-state service-state
                       :plan-state plan-state}))
            [rv session] (plan-fn session)
            [action-plan session] (translate (:action-plan session) session)]
        [action-plan (:plan-state session)]))))

(defn- action-plans-for-nodes
  [service-state environment phase group nodes]
  (if-let [plan-fn (-> group :phases phase)]
    (with-monad state-m
      (domonad
       [action-plans (m-map
                      #(action-plan
                        service-state environment plan-fn {:server {:node %}})
                      nodes)]
       (zipmap nodes action-plans)))
    (fn [plan-state] [nil plan-state])))

(defmulti action-plans
  "Build action plans for the specified `phase` on all nodes or groups in the
  given `target`, within the context of the `service-state`. The `plan-state`
  contains all the settings, etc, for all groups."
  (fn [service-state environment phase target-type target] target-type))

;; Build action plans for the specified `phase` on all nodes in the given
;; `group`, within the context of the `service-state`. The `plan-state` contains
;; all the settings, etc, for all groups.
(defmethod action-plans :group-nodes
  [service-state environment phase target-type group]
  (let [group-nodes (-> service-state :group->nodes (get group))]
    (logging/debugf "action-plans :group-nodes service-state %s" service-state)
    (logging/debugf "action-plans :group-nodes nodes %s" (vec group-nodes))
    (action-plans-for-nodes
     service-state environment phase group group-nodes)))

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
    (action-plan service-state environment plan-fn {:group group})
;; (with-monad state-m
;;       (domonad
;;        [[action-plan plan-state] (action-plan
;;                                   service-state environment plan-fn
;;                                   {:group group})]
;;        [action-plan plan-state]))
    (fn [plan-state] [nil plan-state])))

(defn action-plans-for-phase
  "Build action plans for the specified `phase` on the given `groups`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups.

  The exact format of `groups` depends on `target-type`.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list."
  [service-state environment target-type targets phase]
  (logging/debugf "action-plans-for-phase %s %s %s" target-type phase targets)
  (with-monad state-m
    (domonad
     [action-plans (m-map
                    (partial
                     action-plans
                     service-state environment phase target-type)
                    targets)
      _ (fn [plan-state] (logging/debugf
                          "action-plans-for-phase plans %s"
                          (vec action-plans)))]
     (apply merge-with comp action-plans))))

(defn action-plans-for-phases
  "Build action plans for the specified `phase` on the given `groups`, within
  the context of the `service-state`. The `plan-state` contains all the
  settings, etc, for all groups."
  [target-type service-state environment groups phases]
  (logging/debugf
   "groups %s phases %s" (vec (map :group-name groups)) (vec phases))
  (with-monad state-m
    (domonad
     [action-plans (m-map
                    (partial
                     action-plans-for-phase
                     target-type service-state environment groups)
                    phases)]
     (apply merge-with comp action-plans))))


;;; ## Action Plan Execution
(defmulti session-target-map
  (fn [target-type target]
    target-type))

(defmethod session-target-map :node
  [target-type target]
  {:server {:node target}})

(defmethod session-target-map :group
  [target-type target]
  {:group target})

(defn environment-execution-settings
  "Returns execution settings based purely on the environment"
  [environment]
  (fn [_]
    {:user(:user environment pallet.utils/*admin-user*)
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

(defn execute-action-plan
  "Execute the `action-plan` on the `node`."
  [service-state plan-state user executor execute-status-fn action-plan
   target-type target]
  (logging/debugf "execute-action-plan")
  (with-script-for-node target
    (let [session (merge
                   (session-target-map target-type target)
                   {:service-state service-state
                    :plan-state plan-state
                    :user user})
          [result session] (execute
                            action-plan session executor execute-status-fn)]
      (logging/debugf
       "execute-action-plan returning %s" [(:plan-state session) result])
      {:target target :plan-state (:plan-state session) :result result})))

;;; ## Calculation of node count adjustments
(defn group-delta
  "Calculate actual and required counts for a group"
  [service-state group]
  (logging/debugf
   "group-delta %s %s %s"
   service-state group (vec (-> service-state :group->nodes (get group))))
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
  (logging/debugf "group-deltas %s %s" service-state groups)
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
    (logging/debugf "nodes-to-remove %s" group-deltas)
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
