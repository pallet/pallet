(ns pallet.sync.in-memory-test
  (:require
   [clojure.core.async :refer [chan go <! <!!]]
   [clojure.test :refer :all]
   [pallet.sync :refer :all]
   [pallet.sync.in-memory :refer [in-memory-sync-service]]))


(deftest enter-leave-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {}))
    (is (= {:state :continue} (leave-phase s :q :a))
        "should leave with a :continue state")
    (is (= {:state :continue} (leave-phase s :p :a))
        "should leave with a :continue state")))

(deftest abort-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {}))
    (testing "calling abort-phase"
      (abort-phase s :q :a {:e "e"})
      (let [v (leave-phase s :q :a)]
        (is (= :abort (:state v))
            "should cause leave-phase to return an :abort state")
        (is (= [{:e "e"}] (:reasons v))
            "should cause leave-phase to return the reasons for :abort")))))

(deftest enter-with-guard-test
  (let [s (in-memory-sync-service)]
    (enter-phase-targets s :p [:a])
    (testing "entering a phase with a :guard-fn that returns a falsey value"
      (is (nil? (enter-phase s :q :a {:guard-fn (fn [] nil)}))
          "should indicate the phase should not be run")
      (is (= {:state :continue} (leave-phase s :q :a))
          "should leave with a :continue state"))))

(deftest enter-with-on-complete-test
  (let [s (in-memory-sync-service)
        a (atom nil)]
    (is (enter-phase-targets s :p [:a]))
    (testing "entering a phase with an :on-complete-fn"
      (is (enter-phase s :q :a {:on-complete-fn (fn [] (reset! a true))})
          "should indicate the phase should be run")
      (is (= {:state :continue} (leave-phase s :q :a))
          "should leave with a :continue state")
      (is @a "should have called the :on-complete-fn on completion"))))

(deftest enter-with-on-complete-guard-test
  (let [s (in-memory-sync-service)
        a (atom nil)]
    (enter-phase-targets s :p [:a])
    (testing
        "entering a phase with an :on-complete-fn and a :guard-fn returning nil"
      (is (nil? (enter-phase s :q :a {:on-complete-fn (fn [] (reset! a true))
                                      :guard-fn (fn [] nil)}))
          "should indicate that the phase should not be run")
      (is (= {:state :continue} (leave-phase s :q :a))
          "should leave with a :continue state")
      (is (nil? @a) "should not call the :on-complete-fn"))))

(deftest enter-with-leave-value-test
  (testing "leave-value-fn which always returns an :abort state"
    (let [s (in-memory-sync-service)]
      (is (enter-phase-targets s :p [:a]))
      (is (enter-phase s :q :a {:leave-value-fn (fn [_] {:state :abort})}))
      (is (= {:state :abort} (leave-phase s :q :a))
          "should cause leave-phase to return an :abort state"))))

(deftest in-memory-sync-service-test
  (let [s (in-memory-sync-service)]
    (enter-phase-targets s :p [:a :b])
    (go
     (leave-phase s :p :a))
    (is (= {:state :continue} (leave-phase s :p :b)))))

(deftest all-blocked-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a :b]))
    (is (enter-phase s :q :a {}))
    (is (enter-phase s :r :b {}))
    (testing "calling leave-phase on disjoint sub-phases"
      (let [v (<!! (go
                    (let [c (chan)]
                      (leave-phase s :q :a c)
                      (leave-phase s :r :b c)
                      [(<! c) (<! c)])))]
        (is v "should cause leave to return a value")
        (is (= [:abort :abort] (map :state v))
            "should cause leave to return an :abort state")
        (is (every? :reasons v)
            "should cause leave to return :reasons")
        (is (= [:exception :exception]
               (mapcat (comp #(mapcat keys %) :reasons) v))
            "should cause leave to return an :exception reason")))))
