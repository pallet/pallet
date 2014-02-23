(ns pallet.executors-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [pallet.action :refer [action-fn]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [exec-script remote-file]]
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
  (is (= `({:summary nil
            :action-type :script
            :script [{:language :bash} "f"]
            :form (pallet.actions.decl/exec-script* "f")
            :context nil
            :args ["f"]
            ;; :action-symbol pallet.actions.decl/exec-script*,
            :action
            {:action-symbol pallet.actions.decl/exec-script*,
             :options {}}})
         (plan-data-fn (plan-fn (exec-script "f")))))
  (testing "action options"
    (is (= '{:summary nil
             :action-type :script,
             :script [{:language :bash} "g"],
             :form (pallet.actions.decl/exec-script* "g"),
             :context nil,
             :args ("g"),
             ;; :action-symbol pallet.actions.decl/exec-script*,
             :action
             {:action-symbol pallet.actions.decl/exec-script*,
              :options {}},
             :script-dir "abc",}
           (-> (plan-data-fn (plan-fn
                              (with-action-options {:script-dir "abc"}
                                (exec-script "g"))))
               first
               (dissoc :action-id)))))
  (testing "summary"
    (let [user (System/getProperty "user.name")]
      (is (= {:sudo-user nil
              :script-prefix :sudo
              :action-type :script
              :form `(pallet.actions.decl/remote-file-action
                      "p"
                      {:content "line 1\nline 2"
                       :install-new-files true
                       :overwrite-changes false
                       :owner nil
                       :proxy nil
                       :pallet/new-path
                       ~(str "/var/lib/pallet/home/" user "/p.new")
                       :pallet/md5-path
                       ~(str "/var/lib/pallet/home/" user "/p.md5")
                       :pallet/copy-path
                       ~(str "/var/lib/pallet/home/" user "/p")})
              :context nil
              :args `("p"
                      {:content "line 1\nline 2"
                       :install-new-files true
                       :overwrite-changes false
                       :owner nil
                       :proxy nil
                       :pallet/new-path
                       ~(str "/var/lib/pallet/home/" user "/p.new")
                       :pallet/md5-path
                       ~(str "/var/lib/pallet/home/" user "/p.md5")
                       :pallet/copy-path
                       ~(str "/var/lib/pallet/home/" user "/p")})
              ;; :action-symbol pallet.actions.decl/remote-file-action
              :action
              {:action-symbol 'pallet.actions.decl/remote-file-action
               :options {}}
              :summary "remote-file p :content \"line 1...\""}
             (-> (plan-data-fn (plan-fn
                                (remote-file "p" :content "line 1\nline 2")))
                 first
                 (dissoc :action-id :script)))))))

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
  ;; (testing "with no script comments"
  ;;   (is (= '([{:language :bash} "g"])
  ;;          (-> (echo-fn (plan-fn
  ;;                         (with-action-options {:script-comments false}
  ;;                           (exec-script
  ;;                            ("g")))))))))
  ;; (testing "with no script comments, calling script function"
  ;;   (is (= '([{:language :bash} "which g\ng"])
  ;;          (-> (echo-fn (plan-fn
  ;;                         (with-action-options {:script-comments false}
  ;;                           (exec-script
  ;;                            (lib/which g)
  ;;                            ("g")))))))))
  (testing "with script comments"
    (is (not= '([{:language :bash} "which g\ng"])
              (-> (echo-fn (plan-fn
                             (with-action-options {:script-comments true}
                               (exec-script
                                (lib/which g)
                                ("g"))))))))))
