(ns pallet.operate
  "Operations

## Operation primitive FSM contract

An operation primitive must produce a FSM specification.

The FSM must respond to the :start event in it's initial state. The event data
sent with the :start event will be the current global state. The default initial
state is :init. The default response for the :start event in the :init state is
to set the primitive's :state-data to the global state, and to transition to
the :running state.

The FSM must have a :completed and a :failed state.

It must respond to the :abort event, which is sent on a user initiated abort
of an operation. The :abort event should cause the FSM to end in the :aborted
state.

The :init, :completed, :aborted and :failed states will be implicitly added if
not declared.

State event functions (on-enter and on-exit) should return true if they do
anything to change the state of the FSM, and further event functions should not
be called for the transition."
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [clojure.pprint :only [pprint]]
   [pallet.environment :only [environment]]
   [pallet.computation.event-machine :only [event-machine]]
   [pallet.computation.fsm-dsl
    :only [event-handler event-machine-config initial-state on-enter on-exit
           state valid-transitions configured-states using-fsm-features]]
   [pallet.computation.fsm-utils :only [swap!!]]
   [pallet.map-merge :only [merge-keys merge-key]]
   [pallet.thread :only [executor]]
   [slingshot.slingshot :only [throw+]]))


(def operate-executor (executor {:prefix "operate"
                                 :thread-group-name "pallet-operate"}))

(defn execute
  "Execute a function in the operate-executor thread pool."
  [f]
  (pallet.thread/execute operate-executor f))

(def op-env-key ::env)
(def op-steps-key ::steps)
(def op-todo-steps-key ::todo-steps)
(def op-promise-key ::promise)
(def op-fsm-machines-key ::machines)
(def op-compute-service-key ::compute)
(def op-result-sym-key ::result)
(def op-timeouts-key ::timeouts)
(def op-fail-reason-key ::fail-reason)

;;; ## FSM helpers
(defn update-state
  "Convenience update function."
  [state state-kw f & args]
  (-> (apply update-in state [:state-data] f args)
      (assoc :state-kw state-kw)))

(defn default-init-event-handler
  "Default event handler for the :init state"
  [state event event-data]
  (case event
    :start (assoc state :state-kw :running :state-data event-data)
    :abort (assoc state :state-kw :aborted :state-data event-data)))

(defn do-nothing-event-handler [state _ _] state)

;;; ## Operation Step Processing
(defn- step-fsm
  "Generate a fsm for an operation step."
  [environment {:keys [result-sym op-sym args] :as step}]
  (if-let [f (resolve op-sym)]
    (let [lookup (fn [s]
                   (if (symbol? s)
                     (let [v (environment s ::not-set)]
                       (if (= v ::not-set)
                         (throw+
                          {:reason :could-not-resolve-var :var s}
                          "Could not resolve var %s in operation %s"
                          s op-sym)
                         v))
                     s))]
      (assoc step :fsm (apply f (map lookup args))))
    (throw+
     {:reason :could-not-resolve-operation-step
      :operation-step op-sym}
     "Failed to resolve operation step %s" op-sym)))

(def ^{:doc "Base FSM for primitive FSM."
       :private true}
  default-primitive-fsm
  (event-machine-config
    (initial-state :init)
    (state :init
      (valid-transitions :aborted :running)
      (event-handler default-init-event-handler))
    (state :completed
      (event-handler do-nothing-event-handler))
    (state :aborted
      (event-handler do-nothing-event-handler))
    (state :failed
      (event-handler do-nothing-event-handler))))

(defmethod merge-key ::merge-guarded-chain
  [_ _ val-in-result val-in-latter]
  (fn [state] (when-not (val-in-result state)
                (val-in-latter state))))

(defn merge-fsms
  "Merge operation primitve FSM's."
  [& fsm-configs]
  (apply
   merge-keys
   {:on-enter ::merge-guarded-chain
    :on-exit ::merge-guarded-chain
    :fsm/fsm-features :merge-union}
   fsm-configs))

(defn- wire-step-fsm
  "Wire a fsm configuration to the controlling fsm."
  [{:keys [event] :as op-fsm} step-fsm]
  (assert event)
  (update-in step-fsm [:fsm]
             (fn [fsm]
               (merge-fsms
                default-primitive-fsm
                fsm
                (event-machine-config
                  (state :completed
                    (on-enter (fn op-step-completed [state]
                                (event :step-complete state))))
                  (state :failed
                    (on-enter (fn op-step-failed [state]
                                (event :step-fail state)))))))))

(defn- run-step
  "Run an operation step based on the operation primitive."
  [{:keys [state-data] :as state} {:keys [result-sym] :as step}]
  {:pre [step result-sym]}
  (let [fsm-config (step-fsm (op-env-key state-data) step)
        fsm-config (wire-step-fsm (:em state) fsm-config)
        _ (logging/debugf "run-step config %s" fsm-config)
        {:keys [event] :as fsm} (event-machine (:fsm fsm-config))
        state-data (-> (:state-data state)
                       (update-in [op-todo-steps-key] pop)
                       (update-in [op-fsm-machines-key] conj fsm)
                       (assoc-in [op-result-sym-key] result-sym))]
    (execute (fn run-step-f []
               (logging/tracef
                "Starting operation step: %s result in %s"
                (:op-sym step) result-sym)
               (event :start state-data)))
    (assoc state :state-kw :running :state-data state-data)))

(defn- next-step
  "Return the next primitive to be executed in the operation."
  [state]
  (peek (get-in state [:state-data op-todo-steps-key])))

;;; ## Operation controller FSM
(defn- operate-init
  [state event event-data]
  (case event
    :start (let [state (assoc state :state-data event-data)]
             (if-let [next-step (next-step state)]
               (run-step state next-step)
               (assoc state :state-kw :completed)))))

(defn- operate-running
  [state event event-data]
  (case event
    :step-complete (let [result (get-in event-data [:state-data :result])
                         result-sym (get-in
                                     event-data
                                     [:state-data op-result-sym-key])]
                     (update-state
                      state :step-completed (partial merge-keys {})
                      (update-in event-data [op-env-key]
                                 assoc result-sym result)))
    :step-fail (update-state state :step-failed merge event-data)))

(defn- operate-step-completed
  [state event event-data]
  (case event
    :run-next-step (let [next-step (next-step state)]
                     (run-step next-step))
    :complete (assoc state :state-kw :completed)))

(defn- operate-on-step-completed
  [state]
  (if-let [next-step (next-step state)]
    ((-> state :em :event) :run-next-step nil)
    ((-> state :em :event) :complete nil)))

(defn- operate-step-failed
  [state event event-data]
  (case event
    :fail (assoc state :state-kw :failed)))

(defn- operate-on-step-failed
  [state]
  ((-> state :em :event) :fail nil))

(defn- operate-step-failed
  [state event event-data]
  (case event
    :fail (comment start next step)))

(defn- operate-on-running
  [state]
  (comment TODO main loop for running the operation))

(defn- operate-on-completed
  [state]
  (deliver
   (get-in state [:state-data op-promise-key])
   (get-in
    state
    [:state-data op-env-key (get-in state [:state-data op-result-sym-key])])))

(defn- operate-fsm
  "Construct a fsm for coordinating the steps of the operation."
  [operation initial-environment]
  (let [{:keys [event] :as op-fsm}
        (event-machine
         (event-machine-config
           (initial-state :init)
           (state :init
             (valid-transitions :running :completed)
             (event-handler operate-init))
           (state :running
             (valid-transitions :step-completed :step-failed)
             (on-enter operate-on-running)
             (event-handler operate-running))
           (state :step-completed
             (valid-transitions :completed :running)
             (on-enter operate-on-step-completed)
             (event-handler operate-step-completed))
           (state :step-failed
             (valid-transitions :failed)
             (on-enter operate-on-step-failed)
             (event-handler operate-step-failed))
           (state :completed
             (valid-transitions :completed)
             (on-enter operate-on-completed))
           (state :failed
             (valid-transitions :failed)
             (on-enter operate-on-completed))
           (state :failed
             (valid-transitions :aborted)
             (on-enter operate-on-completed))))
        step-fsms (:steps operation)
        op-promise (promise)
        state-data {op-env-key initial-environment
                    op-steps-key step-fsms
                    op-todo-steps-key (vec step-fsms)
                    op-promise-key op-promise
                    op-fsm-machines-key []
                    op-timeouts-key (atom {})}]
    (logging/debug "Starting operation fsm")
    (event :start state-data)
    [op-fsm op-promise]))

;;; ## User visible interface
(defprotocol Control
  "Operation control protocol."
  (abort [_] "Abort the operation.")
  (status [_] "Return the status of the operation.")
  (complete? [_] "Predicate to test if operation is complete.")
  (wait-for [_] "wait on the result of the completed operation"))

;; Represents a running operation
(deftype Operation
  [fsm completed-promise]
  Control
  (abort [_] ((:event fsm) :abort nil))
  (status [_] ((:state fsm)))
  (complete? [_] (= :completed (:state-kw ((:state fsm)))))
  (wait-for [_] @completed-promise)
  clojure.lang.IDeref
  (deref [_] @completed-promise))

(defn operate
  "Start the specified `operation` on the given arguments. The call returns an
  object that implements the Control protocol."
  [operation & args]
  (let [[{:keys [event] :as fsm} completed-promise]
        (operate-fsm operation (zipmap (:args operation) args))]
    (when-not (= (count (:args operation)) (count args))
      (throw
       (IllegalArgumentException.
        (str "Operation " (:op-name operation)
             " expects " (vec (:args operation))))))
    (Operation. fsm completed-promise)))

(defn report-operation
  "Print a report on the status of an operation."
  [operation]
  (println "------------------------------")
  (let [status (status operation)
        state-data (:state-data status)
        steps (vec (map :op-sym (op-steps-key state-data)))]
    (println "current state: " (:state-kw status))
    (println "steps:" steps)
    (doseq [[step machine] (map vector steps (op-fsm-machines-key state-data))
            :let [state ((:state machine))]]
      (println step (:state-kw state)))
    (println "env:")
    (pprint (op-env-key state-data)))
  (println "------------------------------"))


;;; ## Fundamental primitives
(defn delay-for
  "An operation primitive that does nothing for the given `delay`. This uses the
  stateful-fsm's timeout mechanism. Not the timeout primitive. The primitive
  transitions to :completed after the given delay."
  [delay delay-units]
  (letfn [(init [state event event-data]
            (logging/debugf "delay-for init: event %s" event)
            (case event
              :start (assoc state
                       :state-kw :running
                       :timeout {delay-units delay}
                       :state-data event-data)))
          (timed-out [{:keys [em] :as state}]
            (logging/debug "delay-for timed out, completed.")
            ((:transition em) #(assoc % :state-kw :completed)))]
    (event-machine-config
      (state :init
        (valid-transitions :running)
        (event-handler init))
      (state :running
        (valid-transitions :completed :aborted :timed-out)
        (event-handler do-nothing-event-handler))
      (state :timed-out
        (valid-transitions :completed :aborted)
        (on-enter timed-out)))))


;;; ## Higher order primitives
(def scheduled-executor (executor {:prefix "op-sched"
                                   :thread-group-name "pallet-operate"
                                   :pool-size 3}))

(defn execute-after
  "Execute a function after a specified delay in the scheduled-executor thread
  pool. Returns a ScheduledFuture."
  [f delay delay-units]
  (pallet.thread/execute-after scheduled-executor f delay delay-units))

(defn timeout
  "Execute an expression with a timeout. The timeout is applied to each
  state. Any transition out of a state will cancel the timeout."
  [fsm-config delay delay-units]
  (letfn [(add-timeout [timeout-name]
            (fn add-timeout [{:keys [fsm state-data] :as state}]
              (let [f (execute-after
                       #((:transition fsm)
                         (update-state
                          state :failed
                          assoc op-fail-reason-key {:reason :timed-out})))]
                (swap!
                 (op-timeouts-key state-data)
                 assoc timeout-name f))))
          (remove-timeout [timeout-name]
            (fn remove-timeout [{:keys [state-data] :as state}]
              (let [[to-map _] (swap!!
                                (op-timeouts-key state-data)
                                dissoc timeout-name)]
                (try
                  (future-cancel (timeout-name to-map))
                  (catch Exception e
                    (logging/warnf
                     e "Problem cancelling timeout %s" timeout-name))))))
          (add-timeout-transitions [state-kw]
            (let [timeout-name (gensym (str "to-" (name state-kw)))]
              (event-machine-config
                (state state-kw
                  (on-enter (add-timeout timeout-name))
                  (on-exit (remove-timeout timeout-name))))))]
    (->>
     (configured-states fsm-config)
     (remove #{:completed :failed})
     (map add-timeout-transitions)
     (reduce merge-fsms fsm-config))))

(comment
  (defn collect
    "Execute a set of fsms"
    [fsm-configs]
    (letfn [(start-fsms [{:keys [em] :as state}]
              (doseq [fsm-config fsm-configs
                      :let [fsm-config (wire-colleted em fsm-config)
                            {:keys [event] :as fsm} (event-machine fsm-config)]]
                (event :start state)))]
      (event-machine-config
        (state :init
          (valid-transitions :running))
        (state :running
          (valid-transitions :completed :aborted)
          (on-enter start-fsms)
          (event-handler running))))))
