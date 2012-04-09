(ns pallet.operate
  "Operations"
  (:use
   [pallet.environment :only [environment]]
   [pallet.computation.event-machine :only [event-machine]]
   [pallet.computation.fsm-dsl
    :only [event-handler event-machine-config initial-state on-enter state
           valid-transitions]]
   [pallet.map-merge :only [merge-keys]]
   [slingshot.slingshot :only [throw+]]))

(def op-env-key ::env)
(def op-steps-key ::steps)

(defn wire-step-fsm
  [{:keys [event] :as op-fsm} step-fsm]
  (-> step-fsm
      (merge-keys
       {}
       (event-machine-config
         (state :completed
           (on-enter #(event :step-complete %)))
         (state :aborted
           (on-enter #(event :step-abort %)))))))

(defn operate-init
  [state event event-data]
  (case event
    :start (assoc state :state-kw :running :state-data event-data)))

(defn operate-running
  [state event event-data]
  (case event
    :step-complete (comment start next step)
    :step-abort (comment abort)))

(defn operate-on-running
  [state]
  (comment TODO main loop for running the operation))

(defn step-fsm
  "Generate a fsm for an operation step."
  [{:keys [result-sym op-sym args] :as step}]
  (if-let [f (resolve op-sym)]
    (assoc step :fsm (event-machine (f)))
    (throw+
     {:reason :could-not-resolve-operation-step
      :operation-step op-sym}
     "Failed to resolve operation step %s" op-sym)))

(defn operate-fsm
  "Construct a fsm for coordinating the steps of the operation."
  [operation initial-environment]
  (let [{:keys [event] :as op-fsm}
        (event-machine
         (event-machine-config
           (initial-state :init)
           (state :init
             (valid-transitions :running)
             (event-handler operate-init))
           (state :running
             (valid-transitions :completed :aborted)
             (on-enter operate-on-running)
             (event-handler operate-running))
           (state :completed
             (valid-transitions :completed))
           (state :aborted
             (valid-transitions :aborted))))
        step-fsms (->> (:steps operation)
                       (map step-fsm)
                       (map (partial wire-step-fsm op-fsm)))
        state-data {op-env-key initial-environment
                    op-steps-key step-fsms}]
    (event :start state-data)
    op-fsm))

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
  [fsm]
  Control
  (abort [_] ((:event fsm) :abort nil))
  (status [_] ((:state fsm)))
  (complete? [_] (= :completed (:state-kw ((:state fsm)))))
  (wait-for [_] (comment TODO))
  clojure.lang.IDeref
  (deref [_] (do
               (comment TODO block on completed)
               ((:state fsm)))))

(defn execute-operation [op args]
  (let [{:keys [event] :as fsm} (operate-fsm op (zipmap (:args op) args))]
    (assert (= (count (:args op)) (count args)))
    (Operation. fsm)))

(defn operate
  "Start the specified `operation` on the given groups. `operation` is a keyword
  specifiying a key in the environment operations map."
  [compute operation & args]
  ;; Build the effective environment for each group. The config.clj global
  ;; environment should already be in the compute service's environment, so
  ;; do not read the config file again.
  (if-let [op (-> (environment compute) :operations (get operation))]
    (execute-operation op args)
    (throw+
     {:reason :operation-not-found
      :operation operation}
     "operate called for unknown operation %s" operation)))
