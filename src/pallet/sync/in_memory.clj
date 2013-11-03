(ns pallet.sync.in-memory
  "An in memory phase synchronisation service."
  (:require
   [clojure.core.async :refer [put!]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.sync.protocols :as protocols]))

(defn current-phase
  "Return the current phase vector for the targets."
  [target-map target]
  {:pre [(map? target-map)]}
  (:phase (target-map target)))

(defn current-phases
  "Return a sequence of distinct current phase vectors."
  [target-map]
  {:pre [(map? target-map)]}
  (distinct (map :phase (vals target-map))))

(defn common-current-phase
  "Return the common current phase vector for the targets."
  [target-map targets]
  {:pre [(map? target-map)]}
  (let [phase-vectors (distinct (map #(current-phase target-map %) targets))]
    (when (> (count phase-vectors) 1)
      (throw
       (ex-info
        (str "Heterogenous phases found for enter-phase. "
             (pr-str (vec phase-vectors)))
        {:op :phase-sync/enter-phase
         :reason :multiple-phase-vectors
         :targets targets
         :phase-vectors phase-vectors})))
    (first phase-vectors)))

(defn push-phase
  "Push the phase for the specified nodes."
  [state phase targets options guard]
  (reduce
   (fn [s target]
     (as->
      s s
      (update-in s [:target-state target :phase] (fnil conj []) phase)
      (update-in s [:target-state target :options] (fnil conj []) options)
      (assoc-in s [:target-state target :guard] guard)))
   state targets))

(defn set-blocked
  "Set the target as blocked on exit of phase."
  [state target synch-ch]
  {:pre [(not (:blocked ((:target-state state) target)))]}
  (assoc-in state [:target-state target :blocked] synch-ch))

(defn set-aborted
  "Set the target as aborted for the phase."
  [state target reason]
  {:pre [(not (:blocked ((:target-state state) target)))]}
  (assoc-in state [:target-state target :aborted] reason))

(defn in-phase
  "Return a predicate for a target being in the specified phase subtree."
  [phase]
  (fn [[target state]]
    {:pre [(:phase state)]}
    (= phase (subvec (:phase state) 0 (count phase)))))

(defn blocked-on-phase
  "Return a predicate for a target being blocked in the specified state."
  [phase]
  (fn [[target state]]
    (and (= phase (:phase state))
         (:blocked state))))

(defn all-targets-with-common-parent-blocked?
  "Predicate for all targets with the parent phase of phase being blocked
  on the current phase."
  [state phase]
  {:pre [(seq phase)
         (seq (:target-state state))]}
  (let [parent-phase (pop phase)]
    (every? (blocked-on-phase phase)
            (filter (in-phase parent-phase) (:target-state state)))))

(defn all-blocked?
  "Predicate for all targets being blocked."
  [state]
  {:pre [(seq (:target-state state))]}
  (every? :blocked (vals (:target-state state))))

(defn unblock-target
  [state phase]
  {:pre [(:blocked state)]}
  (-> state
      (assoc :unblock (:blocked state))
      (dissoc :blocked)))

(defn unblock-phase
  "Release the specified phase, associng the :unblock key with the
  sequence of channels that should be released."
  [state phase]
  (reduce
   (fn [s [target state :as entry]]
     (let [async-ch (get-in s [:target-state target :blocked])
           phase-vector (get-in s [:target-state target :phase])]
       (assert async-ch "Unblocking target which is not blocked")
       (as->
        s s
        (update-in s [:target-state target] dissoc :blocked)
        (update-in s [:target-state target :phase] pop)
        (update-in
         s [:unblock]
         assoc target
         (assoc (select-keys (get-in s [:target-state target])
                             [:aborted :guard])
           :options (last (get-in s [:target-state target :options]))
           :async-ch async-ch))
        (update-in s [:target-state target :options] pop)
        (update-in s [:target-state target] dissoc :aborted :guard))))
   (assoc state :unblock {})
   (filter (in-phase phase) (:target-state state))))

(defn default-leave-value
  [unblock]
  (if (some :aborted (vals unblock))
    {:state :abort
     :reasons (->> (vals unblock) (filter :aborted) (map :aborted))}
    {:state :continue}))

(defn release-targets
  [state]
  {:pre [(seq (:unblock state))]}
  (doseq [[target {:keys [async-ch aborted options guard]}] (:unblock state)
          :let [leave-value-fn (or (:leave-value-fn options)
                                   default-leave-value)
                v (leave-value-fn (:unblock state))]]
    (assert (:state v) "invalid leave-value-fn return value")
    (when (= :continue (:state v))
      (when-let [on-complete-fn (and guard (:on-complete-fn options))]
        (on-complete-fn)))
    (put! async-ch v)))

(defn release-with-blocked-error
  [state]
  (let [phases (vec (current-phases state))
        e (ex-info
           (str
            "Phase progress is undecidable, and all targets blocked."
            "  Add phase synchronisation to enable progress."
            "  Current phases: " phases)
           {:op :phase-sync/leave-phase
            :reason :all-blocked
            :targets (keys state)
            :phase-vectors phases})
        v {:state :abort
           :reasons [{:exception e}]}]
    (doseq [[target {:keys [blocked phase]}] (:target-state state)]
      (assert blocked
              "target must be blocked to be released with all-blocked error")
      (debugf "release %s %s" phase target)
      (put! blocked v))))

;; # InMemorySyncService

;; The state is a map with :target-state and possibly :unblock keys.

;; The :target-state is a map from target to :phase, :options and
;; possibly :blocked, and :aborted keywords.  The :unblock key is used
;; as return value from swap! to ensure idempotent actions following
;; the swap!.

(deftype InMemorySyncService [state]
  protocols/SyncService
  (enter-phase [_ phase targets options]
    (debugf "enter-phase phase: %s targets: %s options: %s"
            phase targets options)
    (let [previous-phase (common-current-phase (:target-state @state) targets)
          guard-fn (:guard-fn options)
          guard (if guard-fn (guard-fn) true)]
      (swap! state push-phase phase targets options guard))
    (if-let [guard-fn (:guard-fn options)]
      (guard-fn)
      true))
  (leave-phase [_ phase target synch-ch]
    (debugf "leave %s %s" phase target)
    (let [new-state (swap! state set-blocked target synch-ch)
          phase-vector (current-phase (:target-state new-state) target)]
      (assert (seq phase-vector)
              (str "Leave on empty phase stack: " @state))
      (assert (= phase (last phase-vector))
              (str "Mismatch in phase stack: " phase ", " phase-vector))
      (if (all-targets-with-common-parent-blocked? new-state phase-vector)
        (let [new-state (swap! state unblock-phase phase-vector)]
          (debugf "leave %s %s release" phase target)
          (tracef "leave with new-state %s" new-state)
          (release-targets new-state))
        (when (all-blocked? new-state)
          (debugf "leave %s %s all blocked" phase target)
          (tracef "leave with new-state %s" new-state)
          (release-with-blocked-error new-state)))))
  (abort-phase [_ phase target reason]
    (swap! state set-aborted target reason))
  protocols/StateDumper
  (dump-state [_] @state))

(defn in-memory-sync-service
  []
  (InMemorySyncService. (atom {:target-state {}})))
