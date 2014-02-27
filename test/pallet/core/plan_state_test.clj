(ns pallet.core.plan-state-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.plan-state :refer :all]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [simple-check.core :as sc]
   [simple-check.generators :as gen]
   [simple-check.properties :as prop]))

(deftest sort-scopes-test
  (is (= [[[:host :h] :hv] [[:group :g] :gv] [[:service :s] :sv]]
         (sort-scopes
          [[[:group :g] :gv] [[:service :s] :sv] [[:host :h] :hv]]))))

(deftest merge-scopes-test
  (is (= {:p :hv}
         (merge-scopes {[:group :g] {:p :gv}
                        [:service :s] {:p :sv}
                        [:host :h] {:p :hv}}))))

(def gen-in-memory
  (gen/fmap in-memory-plan-state (gen/map gen/keyword gen/keyword)))

(def gen-impl
  (gen/one-of [gen-in-memory]))

(def plan-state-read-write-test
  (prop/for-all [plan-state gen-impl
                 sk gen/keyword
                 sv gen/keyword
                 v gen/keyword]
    (update-scope plan-state sk sv (fn [_] v))
    (= v (get-scope plan-state sk sv nil :default))))

(deftest in-memory-test
  (testing "Reading back changed plan-state scope"
    (let [result (sc/quick-check 100 plan-state-read-write-test)]
      (is (nil? (:fail result)))
      (is (:result result)))))
