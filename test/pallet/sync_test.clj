(ns pallet.sync-test
  (:require
   [clojure.core.async :refer [go thread]]
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

(deftest sync-phase*-test
  (let [s (in-memory-sync-service)
        fs (map test-fn (range 3))]
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
