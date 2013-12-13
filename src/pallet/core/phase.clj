(ns pallet.core.phase
  "Everything to do with phases in pallet.")

(ann phase-args [Phase -> (Nilable (Seqable Any))])
(defn phase-args [phase]
  (if (keyword? phase)
    nil
    (rest phase)))

;; TODO remove :no-check when core.typed can handle first with a Vector*
(ann ^:no-check phase-kw [Phase -> Keyword])
(defn- phase-kw [phase]
  (if (keyword? phase)
    phase
    (first phase)))

;; TODO remove no-check when get gets smarter
(ann ^:no-check target-phase [PhaseTarget Phase -> [Any * -> Any]])
(defn target-phase [target phase]
  (tracef "target-phase %s %s" target phase)
  ;; TODO switch back to keyword invocation when core.typed can handle it
  (get (get target :phases) (phase-kw phase)))
