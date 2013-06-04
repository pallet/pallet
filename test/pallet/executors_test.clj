(ns pallet.executors-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [pallet.action :refer [action-fn with-action-options]]
   [pallet.actions :refer [exec-script remote-file plan-when plan-when-not]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :refer [nodes]]
   [pallet.executors :refer :all]
   [pallet.script.lib :as lib]
   [pallet.test-utils :refer [make-localhost-compute]]))


(use-fixtures :once (logging-threshold-fixture))

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
            :summary nil
            :action-type :script
            :script [{:language :bash} "f"]
            :form (pallet.actions/exec-script* "f")
            :context nil
            :args ["f"]
            :action-symbol pallet.actions/exec-script*,
            :action
            {:action-symbol pallet.actions/exec-script*,
             :execution :in-sequence
             :precedence {}}})
         (plan-data-fn (plan-fn (exec-script "f")))))
  (is (= '({:location :origin,
            :summary nil
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
               :summary nil
               :form (pallet.actions/exec-script* "f"),
               :context ("plan-when"),
               :args ("f"),
               :action-symbol pallet.actions/exec-script*,
               :action
               {:action-symbol pallet.actions/exec-script*,
                :execution :in-sequence,
                :precedence {}}}]
             []],
            :context ("plan-when"),
            :args (true),
            :action-symbol pallet.actions-impl/if-action,
            :action
            {:action-symbol pallet.actions-impl/if-action,
             :execution :in-sequence,
             :precedence {}}})
         (plan-data-fn (plan-fn
                         (plan-when (= 1 1)
                           (exec-script "f"))))))
  (is (= '({:location :origin,
            :summary nil
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
               :summary nil
               :form (pallet.actions/exec-script* "g"),
               :context ("plan-when-not"),
               :args ("g"),
               :action-symbol pallet.actions/exec-script*,
               :action
               {:action-symbol pallet.actions/exec-script*,
                :execution :in-sequence,
                :precedence {}}}]],
            :context ("plan-when-not"),
            :args (true),
            :action-symbol pallet.actions-impl/if-action,
            :action
            {:action-symbol pallet.actions-impl/if-action,
             :execution :in-sequence,
             :precedence {}}})
         (plan-data-fn (plan-fn
                         (plan-when-not (= 1 1)
                           (exec-script "g"))))))
  (testing "action options"
    (is (= '{:location :target,
             :summary nil
             :action-type :script,
             :script [{:language :bash} "g"],
             :form (pallet.actions/exec-script* "g"),
             :context nil,
             :args ("g"),
             :action-symbol pallet.actions/exec-script*,
             :action
             {:action-symbol pallet.actions/exec-script*,
              :execution :in-sequence,
              :precedence {}},
             :script-dir "abc",}
           (-> (plan-data-fn (plan-fn
                               (with-action-options {:script-dir "abc"}
                                 (exec-script "g"))))
               first
               (dissoc :action-id)))))
  (testing "summary"
    (is (= '{:location :target,
             :sudo-user nil
             :script-prefix :sudo,
             :action-type :script,
             :form (pallet.actions-impl/remote-file-action
                    "p"
                    {:content "line 1\nline 2",
                     :install-new-files true,
                     :overwrite-changes nil,
                     :owner nil}),
             :context nil,
             :args ("p"
                    {:content "line 1\nline 2",
                     :install-new-files true,
                     :overwrite-changes nil,
                     :owner nil}),
             :action-symbol pallet.actions-impl/remote-file-action
             :action
             {:action-symbol pallet.actions-impl/remote-file-action
              :execution :in-sequence,
              :precedence {}}
             :summary "remote-file p :content \"line 1...\""}
           (-> (plan-data-fn (plan-fn
                               (remote-file "p" :content "line 1\nline 2")))
               first
               (dissoc :action-id :script))))))

(defn echo-fn [f]
  (let [compute (make-localhost-compute :group-name "local")
        op (lift (group-spec "local")
                 :phase f
                 :compute compute
                 :environment {:algorithms
                               {:executor echo-executor}})]
    (clojure.tools.logging/infof "op %s" op)
    (-> op :results last :result)))

(deftest action-comments-test
  (testing "with no script comments"
    (is (= '([{:language :bash} "g"])
           (-> (echo-fn (plan-fn
                          (with-action-options {:script-comments false}
                            (exec-script
                             ("g")))))))))
  (testing "with no script comments, calling script function"
    (is (= '([{:language :bash} "which g\ng"])
           (-> (echo-fn (plan-fn
                          (with-action-options {:script-comments false}
                            (exec-script
                             (lib/which g)
                             ("g")))))))))
  (testing "with script comments"
    (is (not= '([{:language :bash} "which g\ng"])
              (-> (echo-fn (plan-fn
                             (with-action-options {:script-comments true}
                               (exec-script
                                (lib/which g)
                                ("g"))))))))))
