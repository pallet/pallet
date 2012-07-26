(ns pallet.core.operations-test
  (:use
   clojure.test
   pallet.core.operations
   [pallet.actions :only [exec-script]]
   [pallet.algo.fsmop :only [failed? operate]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.compute :only [nodes]]
   [pallet.node :only [group-name]]
   [pallet.test-utils :only [clj-action make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(clj-action [session]
       (clojure.tools.logging/info (format "Seenfn %s" name))
       (is (not @seen))
       (reset! seen true)
       [true session])
     seen?]))

(deftest lift-test
  (testing "lift a phase for a node in a group"
    (let [compute (make-localhost-compute)
          group (group-spec
                 (group-name (first (nodes compute)))
                 :phases {:p (plan-fn (exec-script "ls /"))})
          node-set @(operate (group-nodes compute [group]))
          op (operate (lift node-set [:p] {} {}))
          {:keys [plan-state results targets]} @op]
      (is (not (failed? op)))
      (is (= 1 (count targets)))
      (is (= 1 (count (:node-values plan-state))))
      (is (some
           (partial re-find #"bin")
           (->> (mapcat :result results) (map :out))))))
  (testing "lift two phases for a node in a group"
    (let [compute (make-localhost-compute)
          [localf seen?] (seen-fn "lift-test")
          group (group-spec
                 (group-name (first (nodes compute)))
                 :phases {:p (plan-fn (exec-script "ls /"))
                          :p2 (plan-fn (localf))})
          node-set @(operate (group-nodes compute [group]))
          op (operate (lift node-set [:p :p2] {} {}))
          {:keys [plan-state results targets]} @op]
      (is (not (failed? op)))
      (is (= 1 (count targets)))
      (is (= 2 (count (:node-values plan-state))))
      (let [r (mapcat :result results)]
        (is (re-find #"bin" (-> r first :out)))
        (is (= true (second r)))))))
