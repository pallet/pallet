(ns pallet.spec-test
  (:require
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.node :as node]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :refer :all]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.user :as user]
   [schema.core :as schema :refer [validate]]))


(deftest server-spec-test
  (is (server-spec {}))
  (is (server-spec {:phases {:x (plan-fn [_]) :y (plan-fn [_])}}))
  (let [spec (server-spec {:phases {:x (plan-fn [_])}
                           :phases-meta {:x {:m 1}}})]
    (is (= {:m 1} (meta (-> spec :phases :x)))))
  (let [f (fn [] :f)]
    (is (= {:phases {:a f} :default-phases [:configure]}
           (server-spec {:phases {:a f}})))
    (testing "phases-meta"
      (let [spec (server-spec {:phases {:a f}
                               :phases-meta {:a {:phase-execution-f f}}})]
        (is (= :f ((-> spec :phases :a))))
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "phases-meta extension"
      (let [spec1 (server-spec {:phases {:a f}
                                :phases-meta {:a {:phase-execution-f f}}})
            spec2 (server-spec {:phases {:a #()}})
            spec (server-spec {:extends [spec1 spec2]})]
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "default phases-meta"
      (let [spec (server-spec {:phases {:bootstrap f}})]
        (is (= (:bootstrap default-phase-meta)
               (-> spec :phases :bootstrap meta)))))
    (is (= {:phases {:a f} :default-phases [:configure]}
           (server-spec {:extends (server-spec {:phases {:a f}})}))
        "extends a server-spec"))
  (testing ":roles"
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles :r1})) "Allow roles as keyword")
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles [:r1]})) "Allow roles as sequence")
    (is (= {:roles #{:r1} :default-phases [:configure]}
           (server-spec {:roles #{:r1}})) "Allow roles as et"))
  (testing "type"
    (is (= :pallet.spec/server-spec (type (server-spec {:roles :r1}))))))

(deftest spec-for-target-test
  (testing "two specs with different roles"
    (let [s1 (server-spec {:roles :r1})
          s2 (server-spec {:roles :r2})]
      (testing "predicates that macth different nodes"
        (let [predicate-spec-pairs [[(fn [n] (= :ubuntu
                                                (-> n :os-family))) s1]
                                    [(fn [n] (= 22 (node/ssh-port n))) s2]]]
          (testing "node matching one spec"
            (let [n1 {:id "n1" :os-family :centos :ssh-port 22
                      :packager :apt}]
              (is (= (server-spec {:extends [s2]})
                     (spec-for-target predicate-spec-pairs n1))
                  "Target has correct roles")))
          (testing "node matching two specs"
            (let [n2 {:id "n2" :os-family :ubuntu :ssh-port 22
                      :packager :apt}]
              (is (= (server-spec {:extends [s1 s2]})
                     (spec-for-target predicate-spec-pairs n2))
                  "Target has correct roles"))))))))

(deftest phase-args-test
  (is (= nil (phase-args :x)))
  (is (= [] (phase-args [:x])))
  (is (= [:a :b] (phase-args [:x :a :b]))))

(deftest phase-kw-test
  (is (= :x (phase-kw :x)))
  (is (= :x (phase-kw [:x])))
  (is (= :x (phase-kw [:x :a :b]))))
