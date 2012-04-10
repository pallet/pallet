(ns pallet.operation.primitives
  "Base operation primitives for pallet."
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [pallet.core.api :only [query-nodes]]
   [pallet.computation.fsm-dsl :only
    [event-handler event-machine-config state initial-state on-enter
     state-driver valid-transitions]]
   [pallet.operate :only [execute op-compute-service-key]]))

;;; Provide support for controlling retry count, standoff, etc

(defn available-nodes
  "Define an operation that builds a representation of the available nodes."
  [compute groups]
  (letfn [(running [state event event-data]
            (case event
              :success (-> state
                          (assoc :state-kw :completed)
                          (assoc-in [:state-data :result] event-data))
              :fail (assoc state :state-kw :failed)
              :abort (assoc state :state-kw :aborted)))
          (query [{:keys [em state-data] :as state}]
            (let [event (:event em)]
              (execute
               #(try
                  (let [node-groups (query-nodes compute groups)]
                    (logging/debugf "query-nodes returned: %s" node-groups)
                    (event :success node-groups))
                  (catch Exception e
                    (logging/warn e "query-nodes failed")
                    (event :fail {:exception e}))))))]
    (event-machine-config
      (state :running
        (valid-transitions :completed :aborted)
        (on-enter query)
        (event-handler running)))))

(defn run-phase-on-node
  "Runs a phase on the specified nodes. The node has to have the phase
from each group it belongs to run on it."
  [node phase-fns]
  (letfn [(running [state event event-data]
            (case event
              :success (-> state
                          (assoc :state-kw :completed)
                          (assoc-in [:state-data :result] event-data))
              :fail (assoc state :state-kw :failed)
              :abort (assoc state :state-kw :aborted)))
          (run-phase-on-nodes [{:keys [em state-data] :as state}]
            (let [event (:event em)]
              (execute
               #(try
                  (let [node-groups (query-nodes compute groups)]
                    (logging/debugf "query-nodes returned: %s" node-groups)
                    (event :success node-groups))
                  (catch Exception e
                    (logging/warn e "query-nodes failed")
                    (event :fail {:exception e}))))))]
    (event-machine-config
      (state :running
        (valid-transitions :completed :aborted)
        (on-enter run-phase-on-nodes)
        (event-handler running)))))


(defn run-phase
  "Runs a phase on a specified set of nodes. Each node has to have the phase
from each group it belongs to run on it. Collects a set of run-phase-on-node
primitives"
  [nodes phase groups]
  (collect (map #(run-phase-on-node (phase-fns % phase groups)) nodes)))
