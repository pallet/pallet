(ns pallet.executors-test
  (:require
   [clojure.test :refer [deftest is]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.actions :refer [exec-script plan-when plan-when-not remote-file]]
   [pallet.executors :refer :all]
   [pallet.test-utils :refer [make-localhost-compute]]))


(defn run-plan-fn [f]
  (let [compute (make-localhost-compute :group-name "local")
        op (lift (group-spec "local")
                 :phase f
                 :compute compute
                 :environment {:algorithms
                               {:executor action-plan-printer}})]
    (-> @op :results first :result)))

(deftest print-action-plan-test
  (is (= '((pallet.actions-impl/remote-file-action
            "f"
            {:content "xxx",
             :install-new-files true,
             :overwrite-changes nil}))
         (run-plan-fn (plan-fn (remote-file "f" :content "xxx")))))
  (is (= '((pallet.actions-impl/if-action
            true
            (pallet.actions/exec-script* "f")
            nil))
         (run-plan-fn (plan-fn
                        (plan-when (= 1 1)
                          (exec-script "f"))))))
  (is (= '((pallet.actions-impl/if-action
            true
            nil
            (pallet.actions/exec-script* "g")))
         (run-plan-fn (plan-fn
                        (plan-when-not (= 1 1)
                          (exec-script "g")))))))

(defn plan-data-fn [f]
  (let [compute (make-localhost-compute :group-name "local")
        op (lift (group-spec "local")
                 :phase f
                 :compute compute
                 :environment {:algorithms
                               {:executor action-plan-data}})]
    (-> @op :results first :result)))

(deftest action-plan-data-test
  (is (= '({:action-symbol pallet.actions-impl/remote-file-action
            :args ["f"
                   {:content "xxx",
                    :install-new-files true,
                    :overwrite-changes nil}]})
         (plan-data-fn (plan-fn (remote-file "f" :content "xxx")))))
  (is (= '({:action-symbol pallet.actions-impl/if-action
            :args [true]
            :blocks [{:action-symbol pallet.actions/exec-script*
                      :args ["f"]}
                     nil]})
         (plan-data-fn (plan-fn
                         (plan-when (= 1 1)
                           (exec-script "f"))))))
  (is (= '({:action-symbol pallet.actions-impl/if-action
            :args [true]
            :blocks [nil
                     {:action-symbol pallet.actions/exec-script*
                      :args ["g"]}]})
         (plan-data-fn (plan-fn
                         (plan-when-not (= 1 1)
                           (exec-script "g")))))))
