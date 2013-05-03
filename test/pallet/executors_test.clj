(ns pallet.executors-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pallet.action :refer [action-fn with-action-options]]
   [pallet.actions :refer [exec-script plan-when plan-when-not]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.compute :refer [nodes]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.executors :refer :all]
   [pallet.test-utils :refer [make-localhost-compute]]))

(defn plan-data-fn [f]
  (let [compute (make-localhost-compute :group-name "local")
        op (lift (group-spec "local")
                 :phase f
                 :compute compute
                 :environment {:algorithms
                               {:executor action-plan-data}})]
    (-> op :results first :result)))

(deftest action-plan-data-test
  (is (= `({:location :target
            :action-type :script
            :script [{:language :bash} "f"]
            :form (pallet.actions/exec-script* "f")
            :context nil
            :args ["f"]
            :action
            {:action-symbol pallet.actions/exec-script*,
             :execution :in-sequence
             :precedence {}}})
          (plan-data-fn (plan-fn (exec-script "f")))))
  (is (= '({:location :origin,
            :action-type :flow/if,
            :script true,
            :form (pallet.actions-impl/if-action
                   true
                   [(pallet.actions/exec-script* "f")]
                   []),
            :blocks
            [[{:location :target,
               :action-type :script,
               :script [{:language :bash} "f"],
               :form (pallet.actions/exec-script* "f"),
               :context ("plan-when"),
               :args ("f"),
               :action
               {:action-symbol pallet.actions/exec-script*,
                :execution :in-sequence,
                :precedence {}}}]
             []],
            :context ("plan-when"),
            :args (true),
            :action
            {:action-symbol pallet.actions-impl/if-action,
             :execution :in-sequence,
             :precedence {}}})
         (plan-data-fn (plan-fn
                         (plan-when (= 1 1)
                           (exec-script "f"))))))
  (is (= '({:location :origin,
            :action-type :flow/if,
            :script true,
            :form
            (pallet.actions-impl/if-action
             true
             []
             [(pallet.actions/exec-script* "g")]),
            :blocks
            [[]
             [{:location :target,
               :action-type :script,
               :script [{:language :bash} "g"],
               :form (pallet.actions/exec-script* "g"),
               :context ("plan-when-not"),
               :args ("g"),
               :action
               {:action-symbol pallet.actions/exec-script*,
                :execution :in-sequence,
                :precedence {}}}]],
            :context ("plan-when-not"),
            :args (true),
            :action
            {:action-symbol pallet.actions-impl/if-action,
             :execution :in-sequence,
             :precedence {}}})
         (plan-data-fn (plan-fn
                         (plan-when-not (= 1 1)
                           (exec-script "g"))))))
  (testing "action options"
    (is (= '{:location :target,
             :action-type :script,
             :script [{:language :bash} "g"],
             :form (pallet.actions/exec-script* "g"),
             :context nil,
             :args ("g"),
             :action
             {:action-symbol pallet.actions/exec-script*,
              :execution :in-sequence,
              :precedence {}},
             :script-dir "abc",}
           (-> (plan-data-fn (plan-fn
                               (with-action-options {:script-dir "abc"}
                                 (exec-script "g"))))
               first
               (dissoc :action-id))))))
