(ns pallet.operation.primitives
  "Base operation primitives for pallet.

An operation should produce a FSM specification. The FSM must respond to
the :start event."
  (:use
   [pallet.core.api :only [query-nodes]]
   [pallet.computation.fsm-dsl :only
    [event-handler event-machine-config state initial-state state-driver
     valid-transitions]]))

;;; Provide a base fsm, with init, start, completed etc

;;; Provide support for controlling retry count, standoff, etc


(defn available-nodes
  "Define an operation that builds a representation of the available nodes."
  []
  (letfn [(init [state event event-data]
            (case event
              :start (assoc state
                       :state-kw :querying :state-data event-data)))
          (querying [state event event-data]
            (case event
              :sucess (-> state
                          (assoc :state-kw :completed)
                          (update-in [:state-data] merge event-data))
              :fail (assoc state :state-kw :failed)))
          (query [{:keys [event] :as state}]
            (try
              (let [node-groups (query-nodes)]
                (event :success node-groups))
              (catch Exception e
                (event :fail {:exception e}))))]
    (event-machine-config
      (initial-state :init)
      (state :init
        (valid-transitions :querying)
        (event-handler init))
      (state :querying
        (valid-transitions :completed)
        (event-handler querying)
        (state-driver query))
      (state :completed))))

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
