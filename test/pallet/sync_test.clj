(ns pallet.sync-test
  (:require
   [clojure.core.async :as async :refer [alts!! go thread timeout <!!]]
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
  (testing "Three tagets with common sub-phase"
    (let [s (in-memory-sync-service)
          fs (map test-fn (range 3))]
      ;; we need to enter top level phases all at once, so the set
      ;; of all targets is known
      (enter-phase-targets s :start (map :i fs))
      (doseq [{:keys [p f a i]} fs]
        (sync-phase* s :a i {:on-complete-fn #(reset! a true)} f))
      (Thread/sleep 200)                ; allow time for phases to run
      (is (every? (comp nil? deref :a) fs)
          "should not sync if no phase function has completed")
      (deliver (:p (first fs)) true)
      (Thread/sleep 200)                ; allow time for phases to run
      (is (every? (comp nil? deref :a) fs)
          "should not sync if only one phase function completes")
      (doseq [{:keys [p]} (rest fs)]
        (deliver p true))
      (Thread/sleep 200)                ; allow time for phases to run
      (is (every? (comp deref :a) fs)
          "should sync if every phase function completes"))))

(deftest single-subphase-complete-test
  (testing "Three targets with common sub-phase"
    (let [s (in-memory-sync-service)
          fs (map nop-test-fn (range 3))]
      (enter-phase-targets s :start (map :i fs))
      (let [chans (for [{:keys [f a i]} fs]
                    (sync-phase*
                     s :a i {:on-complete-fn #(reset! a true)} f))
            t (timeout 1000)
            m (async/merge chans)
            res (repeatedly 3 #(alts!! [m t]))]
        (is (every? #(not= t (second %)) res)
            "should not timeout")
        (is (every? first res)
            "should all leave with a value")
        (is (every? #(:state (first %)) res)
            "should all leave with a :state")
        (is (every? #(= :continue (:state (first %))) res)
            "should all leave with a :continue state")
        (is (every? (comp deref :a) fs) "should all complete")))))

(deftest blocked-test
  (testing "Three targets with distinct sub-phases"
    (let [s (in-memory-sync-service)
          phases (map (comp keyword str char) (range 65 91))
          fs (->> (map nop-test-fn (range 3))
                  (map #(assoc %2 :phase %1) phases))]
      (enter-phase-targets s :start (map :i fs))
      (let [chans (for [{:keys [p f a i phase]} fs]
                    (sync-phase*
                     s phase i {:on-complete-fn #(reset! a true)} f))]
        (let [t (timeout 1000)
              m (async/merge chans)
              res (repeatedly 3 #(alts!! [m t]))
              ]
          (doseq [[v _] res
                  :let [reason (-> v :reasons first :exception ex-data
                                   :reason)]]
            (is v "should not timeout")
            (is (:state v) "should leave with a state")
            (is (= :abort (:state v)) "should leave with an :abort state")
            (is (= :all-blocked reason) "should have thrown :all-blocked")))
        (is (every? (comp nil? deref :a) fs)
            "should not complete any sub phase")))))
