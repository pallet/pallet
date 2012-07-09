(ns pallet.core.operations-test
  (:use
   clojure.test
   pallet.core.operations
   [pallet.actions :only [exec-script]]
   [pallet.algo.fsmop :only [operate]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.compute :only [nodes]]
   [pallet.node :only [group-name]]
   [pallet.test-utils :only [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest lift-test
  (testing "lift on group"
    (let [compute (make-localhost-compute)
          group (group-spec
                 (group-name (first (nodes compute)))
                 :phases {:p (plan-fn (exec-script "ls /"))})
          node-set (group-nodes compute [group])
          op (operate (lift node-set [:p] {} {}))]
      (is @op)
      (some
       (partial re-find #"/bin")
       (->> (mapcat :results @op) (mapcat :out))))))
