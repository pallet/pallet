(ns pallet.sync-test
  (:require
   [clojure.core.async :refer [alts!! go thread timeout]]
   [clojure.test :refer :all]
   [pallet.sync :refer :all]
   [pallet.sync.in-memory :refer [in-memory-sync-service]]))

(defn test-fn
  [i]
  (let [p (promise)]
    {:p p
     :f (fn [] @p)
     :a (atom nil)
     :i i}))

(defn nop-test-fn
  [i]
  {:f (fn [])
   :a (atom nil)
   :i i})

(deftest sync-phase*-test
  (let [s (in-memory-sync-service)
        fs (map test-fn (range 3))]
    ;; we need to enter top level phases all at once, so the set
    ;; of all targets is known
    (enter-phase-targets s :start (map :i fs))
    (doseq [{:keys [p f a i]} fs]
      (thread
       (sync-phase* s :a i {:on-complete-fn #(reset! a true)} f)))
    (Thread/sleep 200) ; allow time for phases to run
    (is (every? (comp nil? deref :a) fs)
        "the phase should not sync if no f has completed")
    (deliver (:p (first fs)) true)
    (Thread/sleep 200) ; allow time for phases to run
    (is (every? (comp nil? deref :a) fs)
        "completing one f should not cause the phase to sync")
    (doseq [{:keys [p]} (rest fs)]
      (deliver p true))
    (Thread/sleep 200) ; allow time for phases to run
    (is (every? (comp deref :a) fs)
        "completing every f should cause the phase to sync")))

(deftest single-subphase-complete-test
  (let [s (in-memory-sync-service)
        fs (map nop-test-fn (range 3))]
    (enter-phase-targets s :start (map :i fs))
    (let [chans (for [{:keys [f a i]} fs]
                  (thread
                   (try
                     (sync-phase*
                      s :a i {:on-complete-fn #(reset! a true)} f)
                     (catch Throwable e
                       e))))]
      (Thread/sleep 200)
      (let [t (timeout 1000)
            res (repeatedly 3 #(alts!! (conj chans t)))]
        (is (every? #(not= t (second %)) res)))
      (is (every? (comp deref :a) fs)))))  ; all complete


(deftest blocked-test
  (let [s (in-memory-sync-service)
        phases (map (comp keyword str char) (range 65 91))
        fs (->> (map nop-test-fn (range 3))
                (map #(assoc %2 :phase %1) phases))]
    (enter-phase-targets s :start (map :i fs))
    (let [chans (for [{:keys [p f a i phase]} fs]
                  (thread
                   (try
                     (sync-phase*
                      s phase i {:on-complete-fn #(reset! a true)} f)
                     (catch Throwable e
                       e))))]
      (Thread/sleep 200)
      (let [[v _] (alts!! (conj chans (timeout 1000)))
            reason (-> v ex-data :reason)]
        (is v)
        (is (= :all-blocked reason)))
      (is (every? (comp nil? deref :a) fs)))))  ; none complete
