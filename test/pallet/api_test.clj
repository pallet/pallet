(ns pallet.api-test
  (:use
   clojure.test
   pallet.api
   [pallet.actions :only [exec-script]]
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
          op (lift [group] :phase :p :compute compute)]
      (is @op)
      (some
       (partial re-find #"/bin")
       (->> (mapcat :results @op) (mapcat :out)))))
  (testing "lift on group with inline plan-fn"
    (let [compute (make-localhost-compute)
          group (group-spec (group-name (first (nodes compute))))
          op (lift [group]
                   :phase (plan-fn (exec-script "ls /"))
                   :compute compute)]
      (is @op)
      (some
       (partial re-find #"/bin")
       (->> (mapcat :results @op) (mapcat :out))))))
