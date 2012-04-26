(ns pallet.operation.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.core.api :as api])
  (:use
   [pallet.computation.fsm-dsl :only
    [event-handler event-machine-config fsm-name initial-state on-enter state
     state-driver valid-transitions]]
   [pallet.operate :only [execute op-compute-service-key update-state map*]]))

;;; Provide support for controlling retry count, standoff, etc,
;;; Although this can be added externally.
(defn async-fsm
  "Returns a FSM specification for running the specified function in a future.
  Assumes failures in the underlying function cause an exception to be thrown,
  and that the function takes no arguments."
  [f]
  (let [async-f (atom nil)]
    (letfn [(running [state event event-data]
              (case event
                :success (update-state
                          state :completed assoc :result event-data)
                :fail (update-state
                       state :failed assoc :fail-reason event-data)
                :abort (do
                         (when-let [async-f @async-f]
                           (future-cancel async-f))
                         (update-state
                          state :aborted assoc :fail-reason event-data))))
            (run-async [{:keys [em state-data] :as state}]
              (let [event (:event em)
                    f-runner (fn []
                               (try
                                 (event :success (f))
                                 (catch Exception e
                                   (logging/warn e "async-fsm failed")
                                   (event :fail {:exception e}))))]
                (reset! async-f (execute f-runner))))]
      (event-machine-config
        (fsm-name (str f))
        (state :running
          (valid-transitions :completed :failed :aborted)
          (on-enter run-async)
          (event-handler running))))))

(defn service-state
  "Define an operation that builds a representation of the available nodes."
  [compute groups]
  (async-fsm (partial api/service-state compute groups)))

(defn execute-action-plan
  "Executes an action-plan on the specified node."
  [service-state plan-state environment target-type [target action-plan]]
  (async-fsm
   (partial
    api/execute-action-plan
    service-state plan-state environment action-plan target-type target)))

;; (defn execute-group-action-plan
;;   "Executes an action-plan on the specified group."
;;   [service-state plan-state environment [group action-plan]]
;;   (async-fsm
;;    (partial
;;     api/execute-action-plan-for-group
;;     service-state plan-state environment action-plan group)))

(defn execute-action-plans
  "Execute action-plans on groups"
  [service-state plan-state environment target-type action-plans]
  (map* (map
         (partial
          execute-action-plan service-state plan-state environment target-type)
         action-plans)))

;; (defn execute-group-action-plans
;;   "Execute action-plans on groups"
;;   [service-state plan-state environment action-plans]
;;   (map* (map
;;          (partial
;;           execute-group-action-plan service-state plan-state environment)
;;          action-plans)))

(defn build-and-execute-phase
  "Build and execute the specified phase.

  `target-type`
  : specifies the type of target to run the phase on, :group, :group-nodes,
  or :group-node-list."
  [service-state plan-state environment target-type targets phase]
  (logging/debugf "build-and-execute-phase %s" targets)
  (let [[action-plans plan-state]
        ((api/action-plans-for-phase
          service-state environment  target-type targets phase)
         plan-state)]
    (logging/debugf "build-and-execute-phase execute")
    (execute-action-plans
     service-state plan-state environment target-type action-plans)))

;; (defn build-and-execute-group-phase
;;   "Build and execute the specified phase."
;;   [plan-state environment groups phase]
;;   (let [[action-plans plan-state]
;;         ((api/action-plans-for-group-phase
;;           service-state environment groups phase)
;;          plan-state)]
;;     (execute-group-action-plans
;;      service-state plan-state environment action-plans)))

(defn create-nodes
  "Create `count` nodes for a `group`."
  [compute-service environment group count]
  (async-fsm
   (partial api/create-nodes compute-service environment group count)))

(defn create-group-nodes
  "Create nodes for group."
  [compute-service environment group-counts]
  (map*
   (map
    #(create-nodes compute-service environment (key %) (val %))
    group-counts)))

(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [compute-service group {:keys [nodes all] :as remove-node-map}]
  (async-fsm
   (partial api/remove-nodes compute-service group remove-node-map)))

(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [compute-service group-nodes]
  (map* (map #(remove-nodes compute-service (key %) (val %)) group-nodes)))


;; (defn service-state
;;   "Define an operation that builds a representation of the available nodes."
;;   [compute groups]
;;   (letfn [(running [state event event-data]
;;             (case event
;;               :success (update-state state :completed assoc :result event-data)
;;               :fail (update-state state :failed assoc :fail-reason event-data)
;;               :abort (update-state
;;                       state :aborted assoc :fail-reason event-data)))
;;           (query [{:keys [em state-data] :as state}]
;;             (let [event (:event em)]
;;               (execute
;;                #(try
;;                   (let [node-groups (api/service-state compute groups)]
;;                     (logging/debugf "service-state returned: %s" node-groups)
;;                     (event :success node-groups))
;;                   (catch Exception e
;;                     (logging/warn e "service-state failed")
;;                     (event :fail {:exception e}))))))]
;;     (event-machine-config
;;       (state :running
;;         (valid-transitions :completed :aborted)
;;         (on-enter query)
;;         (event-handler running)))))
;;
;; (defn execute-action-plan
;;   "Executes an action-plan on the specified node."
;;   [service-state plan-state environment [node action-plan]]
;;   (let [f (atom nil)]
;;     (letfn [(running [state event event-data]
;;               (case event
;;                 :success (update-state
;;                           state :completed assoc :result event-data)
;;                 :fail (update-state
;;                        state :failed assoc :fail-reason event-data)
;;                 :abort (do
;;                          (when-let [f @f]
;;                            (future-cancel f))
;;                          (update-state
;;                           state :aborted assoc :fail-reason event-data))))
;;             (run-phase-on-node [{:keys [em state-data] :as state}]
;;               (let [event (:event em)]
;;                 (reset!
;;                  f
;;                  (execute
;;                   #(try
;;                      (event
;;                       :success
;;                       (api/execute-action-plan
;;                        service-state plan-state environment action-plan node))
;;                      (catch Exception e
;;                        (logging/warn e "execute-action-plan failed")
;;                        (event :fail {:exception e})))))))]
;;       (event-machine-config
;;         (state :running
;;           (valid-transitions :completed :aborted)
;;           (on-enter run-phase-on-node)
;;           (event-handler running))))))


;; (defn run-phase
;;   "Runs a phase on a specified set of nodes. Each node has to have the phase
;; from each group it belongs to run on it. Collects a set of run-phase-on-node
;; primitives"
;;   [nodes phase groups]
;;   (collect (map #(run-phase-on-node (phase-fns % phase groups)) nodes)))
