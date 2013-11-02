(ns pallet.sync.in-memory-test
  (:require
   [clojure.core.async :refer [go]]
   [clojure.test :refer :all]
   [pallet.sync :refer :all]
   [pallet.sync.in-memory :refer [in-memory-sync-service]]))


(deftest enter-leave-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {}))
    (is (= {:state :continue} (leave-phase s :q :a)))
    (is (= {:state :continue} (leave-phase s :p :a)))))

(deftest abort-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {}))
    (abort-phase s :q :a)
    (is (= {:state :abort} (leave-phase s :q :a)))))

(deftest enter-with-guard-test
  (let [s (in-memory-sync-service)]
    (enter-phase-targets s :p [:a])
    (is (nil? (enter-phase s :q :a {:guard-fn (fn [] nil)})))
    (is (= {:state :continue} (leave-phase s :q :a)))))

(deftest enter-with-on-complete-test
  (let [s (in-memory-sync-service)
        a (atom nil)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {:on-complete-fn (fn [] (reset! a true))}))
    (is (= {:state :continue} (leave-phase s :q :a)))
    (is @a "on complete should be called on completion")))

(deftest enter-with-on-complete-guard-test
  (let [s (in-memory-sync-service)
        a (atom nil)]
    (enter-phase-targets s :p [:a])
    (enter-phase s :q :a {:on-complete-fn (fn [] (reset! a true))
                          :guard-fn (fn [] nil)})
    (is (= {:state :continue} (leave-phase s :q :a)))
    (is (nil? @a) "on complete function should not be called if guard nil")))

(deftest enter-with-leave-value-test
  (let [s (in-memory-sync-service)]
    (is (enter-phase-targets s :p [:a]))
    (is (enter-phase s :q :a {:leave-value-fn (fn [_] {:state :abort})}))
    (is (= {:state :abort} (leave-phase s :p :a)))))

(deftest in-memory-sync-service-test
  (let [s (in-memory-sync-service)]
    (enter-phase-targets s :p [:a :b])
    (go
     (leave-phase s :p :a))
    (is (= {:state :continue} (leave-phase s :p :b)))))
