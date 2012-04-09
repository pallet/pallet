(ns pallet.operate
  "Operations"
  (:require
   [clojure.tools.logging :as logging])
  (:use
   [pallet.environment :only [environment]]
   [pallet.computation.event-machine :only [event-machine]]
   [pallet.computation.fsm-dsl
    :only [event-handler event-machine-config initial-state on-enter state
           valid-transitions]]
   [pallet.map-merge :only [merge-keys]]
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

(defn update-state [state state-kw f & args]
  (-> (apply update-in state [:state-data] f args)
      (assoc :state-kw state-kw)))

(defn step-fsm
  "Generate a fsm for an operation step."
  [environment {:keys [result-sym op-sym args] :as step}]
  (if-let [f (resolve op-sym)]
    (let [lookup (fn [s] (let [v (environment s ::not-set)]
                           (if (= v ::not-set)
                             (throw+
                              {:reason :could-not-resolve-var :var s}
                              "Could not resolve var %s in operation %s"
                              s op-sym)
                             v)))]
      (assoc step :fsm (apply f (map lookup args))))
    (throw+
     {:reason :could-not-resolve-operation-step
      :operation-step op-sym}
     "Failed to resolve operation step %s" op-sym)))

(defn wire-step-fsm
  [{:keys [event] :as op-fsm} step-fsm]
  (assert event)
  (update-in step-fsm [:fsm]
             (fn [fsm]
               (merge-keys
                {}
                (event-machine-config
                  (initial-state :init))
                fsm
                (event-machine-config
                  (state :completed
                    (on-enter (fn op-step-completed [state]
                                (event :step-complete state))))
                  (state :aborted
                    (on-enter (fn op-step-aborted [state]
                                (event :step-abort state)))))))))

(defn run-step
  [{:keys [state-data] :as state} {:keys [result-sym] :as step}]
  {:pre [step result-sym]}
  (let [fsm-config (step-fsm (op-env-key state-data) step)
        fsm-config (wire-step-fsm (:em state) fsm-config)
        {:keys [event] :as fsm} (event-machine (:fsm fsm-config))
        state-data (-> (:state-data state)
                       (update-in [op-todo-steps-key] pop)
                       (update-in [op-fsm-machines-key] conj fsm)
                       (assoc-in [op-result-sym-key] result-sym))]
    (execute (fn run-step-f []
               (logging/debugf
                "Starting operation step: %s result in %s"
                (:op-sym step) result-sym)
               (event :start state-data)))
    (assoc state :state-kw :running :state-data state-data)))

(defn next-step
  [state]
  (peek (get-in state [:state-data op-todo-steps-key])))

(defn operate-init
  [state event event-data]
  (case event
    :start (let [state (assoc state :state-data event-data)]
             (if-let [next-step (next-step state)]
               (run-step state next-step)
               (assoc state :state-kw :completed)))))

(defn operate-running
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
    :step-abort (update-state state :step-aborted merge event-data)))

(defn operate-step-completed
  [state event event-data]
  (case event
    :run-next-step (let [next-step (next-step state)]
                     (run-step next-step))
    :complete (assoc state :state-kw :completed)))

(defn operate-on-step-completed
  [state]
  (if-let [next-step (next-step state)]
    ((-> state :em :event) :run-next-step nil)
    ((-> state :em :event) :complete nil)))

(defn operate-step-aborted
  [state event event-data]
  (case event
    :abort (assoc state :state-kw :aborted)))

(defn operate-on-step-aborted
  [state]
  ((-> state :em :event) :abort nil))

(defn operate-step-aborted
  [state event event-data]
  (case event
    :abort (comment start next step)))

(defn operate-on-running
  [state]
  (comment TODO main loop for running the operation))

(defn operate-on-completed
  [state]
  (deliver
   (get-in state [:state-data op-promise-key])
   (get-in
    state
    [:state-data op-env-key (get-in state [:state-data op-result-sym-key])])))

(defn operate-fsm
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
             (valid-transitions :step-completed :step-aborted)
             (on-enter operate-on-running)
             (event-handler operate-running))
           (state :step-completed
             (valid-transitions :completed :running)
             (on-enter operate-on-step-completed)
             (event-handler operate-step-completed))
           (state :step-aborted
             (valid-transitions :aborted)
             (on-enter operate-on-step-aborted)
             (event-handler operate-step-aborted))
           (state :completed
             (valid-transitions :completed)
             (on-enter operate-on-completed))
           (state :aborted
             (valid-transitions :aborted)
             (on-enter operate-on-completed))))
        step-fsms (:steps operation)
        op-promise (promise)
        state-data {op-env-key initial-environment
                    op-steps-key step-fsms
                    op-todo-steps-key (vec step-fsms)
                    op-promise-key op-promise
                    op-fsm-machines-key []}]
    (logging/debug "Starting operation fsm")
    (event :start state-data)
    [op-fsm op-promise]))

;; (defn operate-step
;;   "Perform a step in an operation"
;;   []
;;   ;; For each group, instantiate the FSM for the specified operation.
;;   (comment build group specific fsms)
;;   ;; instantiate a group co-ordination FSM
;;   (comment build group coordination fsm)

;; )

;; (defn implement-operation [f]
;;   (fn implement-operation [state]
;;     state))


(defprotocol Control
  "Operation control protocol"
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

(defn execute-operation [op args]
  (let [[{:keys [event] :as fsm} completed-promise]
        (operate-fsm op (zipmap (:args op) args))]
    (assert (= (count (:args op)) (count args)))
    (Operation. fsm completed-promise)))

;; (defn operate
;;   "Start the specified `operation` on the given groups. `operation` is a keyword
;;   specifiying a key in the environment operations map."
;;   [operation & args]
;;   ;; Build the effective environment for each group. The config.clj global
;;   ;; environment should already be in the compute service's environment, so
;;   ;; do not read the config file again.
;;   (if-let [op (-> (environment compute) :operations (get operation))]
;;     (execute-operation op args)
;;     (throw+
;;      {:reason :operation-not-found
;;       :operation operation}
;;      "operate called for unknown operation %s" operation)))

(defn report-operation
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
    (println "env:" (op-env-key state-data)))
  (println "------------------------------"))
