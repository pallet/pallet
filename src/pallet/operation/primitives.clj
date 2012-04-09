(ns pallet.operation.primitives
  "Base operation primitives for pallet.

An operation should produce a FSM specification. The FSM must respond to
the :start event."
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [pallet.core.api :only [query-nodes]]
   [pallet.computation.fsm-dsl :only
    [event-handler event-machine-config state initial-state on-enter
     state-driver valid-transitions]]
   [pallet.operate :only [execute op-compute-service-key]]))

;;; Provide a base fsm, with init, start, completed etc

;;; Provide support for controlling retry count, standoff, etc


(defn available-nodes
  "Define an operation that builds a representation of the available nodes."
  [compute groups]
  (letfn [(init [state event event-data]
            (case event
              :start (assoc state :state-kw :querying :state-data event-data)))
          (querying [state event event-data]
            (case event
              :success (-> state
                          (assoc :state-kw :completed)
                          (assoc-in [:state-data :result] event-data))
              :fail (assoc state :state-kw :failed)))
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
      (initial-state :init)
      (state :init
        (valid-transitions :querying)
        (event-handler init))
      (state :querying
        (valid-transitions :completed)
        (on-enter query)
        (event-handler querying))
      (state :completed)
      (state :failed))))

(comment
  (defn converge-node-counts
    "Define an operation the adjust node counts to target numbers"
    []
    (letfn [(init [state event event-data]
              (case event
                :start (assoc state
                         :state-kw :converging :state-data event-data)))
            (converging [state event event-data]
              (case event
                :start (assoc state :state-kw :querying)))]
      (event-machine-config
        (initial-state :init)
        (state :init
          (valid-transitions :converging)
          (event-handler init))
        (state :converging
          (valid-transitions :completed)
          (event-handler querying)
          (state-driver query))
        (state :completed)))))
