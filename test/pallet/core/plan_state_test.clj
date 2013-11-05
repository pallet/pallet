(ns pallet.core.plan-state-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.plan-state :refer :all]))

;; (deftest sort-scopes-test
;;   (is (= [[:host :h] [:group :g] [:service :s]]
;;          (sort-scopes {:group :g :service :s :host :h}))))

(deftest sort-scopes-test
  (is (= [[[:host :h] :hv] [[:group :g] :gv] [[:service :s] :sv]]
         (sort-scopes {[:group :g] :gv [:service :s] :sv [:host :h] :hv}))))

(deftest mereg-scopes-test
  (is (= {:p :hv}
         (merge-scopes {[:group :g] {:p :gv}
                        [:service :s] {:p :sv}
                        [:host :h] {:p :hv}}))))

;; (deftest paths-for-test
;;   (is (= [[:host :h]
;;           [:group :g :host :h]]
;;          (paths-for {:host :h :group :g}))))
