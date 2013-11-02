(ns pallet.sync.in-memory-test
  (:require
   [clojure.core.async :refer [go]]
   [clojure.test :refer :all]
   [pallet.sync :refer :all]
   [pallet.sync.in-memory :refer [in-memory-sync-service]]))


(deftest enter-test
  (let [s (in-memory-sync-service)]
    (is (nil? (enter-phase-targets s :p [:a :b])))
    (is (= {:target-state {:b {:phase [:p]}, :a {:phase [:p]}}}
           (dump s)))
    (is (= [:p] (enter-phase s :q :a)))
    (is (= {:target-state {:b {:phase [:p]}, :a {:phase [:p :q]}}}
           (dump s)))))

(deftest in-memory-sync-service-test
  (let [s (in-memory-sync-service)]
    (enter-phase-targets s :p [:a :b])
    (is (= {:target-state {:b {:phase [:p]}, :a {:phase [:p]}}}
           (dump s)))
    (go
     (leave-phase s :p :a))
    (is (= {:state :continue} (leave-phase s :p :b)))))
