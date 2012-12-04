(ns pallet.actions-test
  (:use
   clojure.test
   pallet.actions))

(deftest one-node-filter-test
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]}]
    (is (= {:node 2} (one-node-filter role->nodes [:r :r2]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]}]
    (is (= {:node 3} (one-node-filter role->nodes [:r :r2 :r3]))))
  (let [role->nodes {:r [{:node 1}{:node 2}{:node 3}]
                     :r2 [{:node 2}{:node 3}]
                     :r3 [{:node 1}{:node 3}]
                     :r4 [{:node 4}]}]
    (is (= {:node 1} (one-node-filter role->nodes [:r :r2 :r3 :r4])))))
