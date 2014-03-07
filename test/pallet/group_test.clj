(ns pallet.group-test
  (:require
   [clojure.core.async :refer [<!! chan]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script*]]
   [pallet.actions.test-actions :refer [fail]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core.node-os :refer [node-os]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.crate.os :refer [os]]
   [pallet.group :as group]
   [pallet.plan :refer :all]
   [pallet.session :as session :refer [executor plan-state recorder]]
   [pallet.user :as user]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once
  (logging-threshold-fixture))

;; (deftest service-state-test
;;   (testing "default groups"
;;     (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                    (make-node "n2" "g1" "192.168.1.2" :linux)]
;;           g1 (group-spec :g1)
;;           service (node-list-service [n1 n2])]
;;       (is (= [(assoc g1
;;                 :node (assoc n1 :service service)
;;                 :group-names #{:g1})
;;               (assoc g1
;;                 :node (assoc n2 :service service)
;;                 :group-names #{:g1})]
;;              (service-state service [g1])))))
;;   (testing "custom groups"
;;     (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                    (make-node "n2" "g1" "192.168.1.2" :linux)]
;;           g1 (group-spec
;;               :g1
;;               :node-filter #(= "192.168.1.2" (node/primary-ip %)))
;;           service (node-list-service [n1 n2])]
;;       (is (= [(assoc g1
;;                 :node (assoc n2 :service service)
;;                 :group-names #{:g1})]
;;              (service-state service [g1]))))))

;; (deftest service-state-with-nodes-test
;;   (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                     (make-node "n2" "g1" "192.168.1.2" :linux)
;;                     (make-node "n3" "g1" "192.168.1.3" :linux)]
;;         g1 (group-spec :g1)
;;         service (node-list-service [n1 n2])]
;;     (is (= {:node->groups {n1 [g1] n2 [g1]}
;;             :group->nodes {g1 [n1 n2]}}
;;            (service-state-with-nodes {} {g1 [n1 n2]})))
;;     (is (= {:node->groups {n1 [g1] n2 [g1] n3 [g1]}
;;             :group->nodes {g1 [n3 n1 n2]}}
;;            (service-state-with-nodes
;;              {:node->groups {n3 [g1]} :group->nodes {g1 [n3]}}
;;              {g1 [n1 n2]})))))

;; (deftest service-state-without-nodes-test
;;   (let [[n1 n2 n3] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                     (make-node "n2" "g1" "192.168.1.2" :linux)
;;                     (make-node "n3" "g1" "192.168.1.3" :linux)]
;;         g1 (group-spec :g1)
;;         service (node-list-service [n1 n2 n3])
;;         ss (service-state service [g1])]
;;     (is (= {:node->groups {n1 [g1] n2 [g1]}
;;             :group->nodes {g1 [n1 n2]}}
;;            (service-state-without-nodes ss {g1 {:nodes [n3]}})))))

;; (deftest group-deltas-test
;;   (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                  (make-node "n2" "g1" "192.168.1.2" :linux)]
;;         g1 (group-spec :g1 :count 1)
;;         service (node-list-service [n1 n2])]
;;     (is (= [[g1 {:actual 2 :target 1 :delta -1}]]
;;            (group-deltas (service-state service [g1]) [g1])))))

;; (deftest nodes-to-remove-test
;;   (let [[n1 n2] [(make-node "n1" "g1" "192.168.1.1" :linux)
;;                  (make-node "n2" "g1" "192.168.1.2" :linux)]
;;         g1 (group-spec :g1 :count 1)
;;         service (node-list-service [n1 n2])
;;         service-state (service-state service [g1])]
;;     (is (= {g1 {:nodes [(assoc g1
;;                           :node (assoc n1 :service service)
;;                           :group-names #{:g1})]
;;                 :all false}}
;;            (nodes-to-remove
;;             service-state
;;             (group-deltas service-state [g1]))))))

;; (deftest execute-phase-on-target-test
;;   (let [n1 (make-localhost-node :group-name "g1")
;;         ga (fn test-plan-fn [] 1)
;;         g1 (group-spec :g1
;;              :phases {:p (plan-fn (exec-script "ls /"))
;;                       :g (plan-fn (ga))})
;;         service (node-list-service [n1])
;;         service-state (service-state service [g1])
;;         user (assoc *admin-user* :no-sudo true)]
;;     (testing "nodes"
;;       (let [{:keys [phase plan-state result target] :as r}
;;             (execute-phase-on-target
;;              service-state {:ps 1} {} :p
;;              (fn test-exec-setttings-fn [_ _]
;;                {:user user
;;                 :executor default-executor
;;                 :execute-status-fn stop-execution-on-error})
;;              (assoc g1 :node (:node (first service-state))))]
;;         (is (= {:ps 1} plan-state))
;;         (is (= (dissoc (first service-state) :group-names) target))
;;         (is (= :p phase))
;;         (is (seq result))
;;         (is (:out (first result)))
;;         (is (.contains (:out (first result)) "bin"))))
;;     (testing "group"
;;       (let [group-target (assoc g1 :target-type :group)
;;             {:keys [result phase plan-state target]}
;;             (execute-phase-on-target
;;              service-state {:ps 1} {} :g
;;              (fn test-exec-setttings-fn [_ _]
;;                {:user user
;;                 :executor default-executor
;;                 :execute-status-fn stop-execution-on-error})
;;              group-target)]
;;         (is (= {:ps 1} plan-state))
;;         (is (= group-target target))
;;         (is (= :group (:target-type target)))
;;         (is (= :g phase))
;;         (is (empty? result))))))        ; no actions can run on a group target

(deftest action-plan-test
  ;; (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
  ;;       g1 (group-spec :g1)
  ;;       service (node-list-service [n1])
  ;;       service-state (service-state service [g1])
  ;;       [r plan-state] (with-script-for-node {:node n1} nil
  ;;                        ((action-plan
  ;;                          service-state {}
  ;;                          (plan-fn (exec-script "ls"))
  ;;                          nil
  ;;                          {:server {:node n1}})
  ;;                         {:ps 1}))]
  ;;   (is (seq r))
  ;;   (is (map? plan-state))
  ;;   (is (= {:ps 1} plan-state)))
  ;; (testing "with phase arguments"
  ;;   (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
  ;;       g1 (group-spec :g1)
  ;;       service (node-list-service [n1])
  ;;       service-state (service-state service [g1])
  ;;         [r plan-state] (with-script-for-node {:node n1} nil
  ;;                        ((action-plan
  ;;                          service-state {}
  ;;                          (fn [x] (exec-script "ls" ~x))
  ;;                          ["/bin"]
  ;;                          {:server {:node n1}})
  ;;                         {:ps 1}))]
  ;;   (is (seq r))
  ;;   (is (map? plan-state))
  ;;   (is (= {:ps 1} plan-state))))
  )

;; (deftest action-plans-test
;;   (let [n1 (make-node "n1" "g1" "192.168.1.1" :ubuntu)
;;         g1 (group-spec
;;             :g1
;;             :phases {:p (plan-fn (exec-script "ls"))
;;                      :g (plan-fn 1)})
;;         service (node-list-service [n1])
;;         n1 (assoc n1 :service service)
;;         service-state (service-state service [g1])]
;;     (testing "nodes"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :p service-state) {:ps 1})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (map? plan-state))
;;         (is (= {:ps 1} plan-state))
;;         (is (= 1 (count r)))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :p (:phase r1)))
;;         (is (= (assoc g1 :node n1 :group-names #{:g1}) (:target r1)))))
;;     (testing "nodes"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :p service-state) {})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (= 1 (count r)))
;;         (is (map? plan-state))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :p (:phase r1)))
;;         (is (= (assoc g1 :node n1 :group-names #{:g1}) (:target r1)))))
;;     (testing "group"
;;       (let [[r plan-state] ((action-plans
;;                              service-state {} {} :g
;;                              [(assoc g1 :target-type :group)])
;;                             {})
;;             r1 (first r)]
;;         (is (seq r))
;;         (is (= 1 (count r)))
;;         (is (map? plan-state))
;;         (is (map? r1))
;;         (is (:action-plan r1))
;;         (is (= :g (:phase r1)))
;;         (is (= (assoc g1 :target-type :group) (:target r1)))
;;         (is (= :group (-> r1 :target :target-type)))))))

;; (deftest lift-op-test
;;   (let [session (session/create {:executor (ssh/ssh-executor)
;;                                  :plan-state (in-memory-plan-state)})
;;         host (localhost)]
;;     (testing "Successful, single phase lift-op"
;;       (let [g (group/group-spec :g
;;                 :phases {:x (plan-fn [session] (exec-script* session "ls"))})
;;             t (assoc g :node host)
;;             result (group/lift-op session [:x] [t] {})]
;;         (is result "returns a result")
;;         (is (nil? (some #(some :error (:action-results %)) result))
;;             "has no action errors")))
;;     (testing "Unsuccessful, single phase lift-op"
;;       (let [g (group/group-spec :g
;;                 :phases {:x (plan-fn [session] (fail session))})
;;             t (assoc g :node host)
;;             result (group/lift-op session [:x] [t] {})]
;;         (is result "returns a result")
;;         (is (some #(some :error (:action-results %)) result)
;;             "returns an action error")))
;;     (testing "Throws on exception if a plan-fn throws"
;;       (let [g (group/group-spec :g
;;                 :phases {:x (plan-fn [session] (throw (ex-info "Test" {})))})
;;             t (assoc g :node host)]
;;         (is (thrown? Exception (group/lift-op session [:x] [t] {})))))))

(deftest service-state-test
  (testing "service state"
    (let [service (make-localhost-compute :group-name :local)
          ss (group/service-state
              (compute/nodes service) [(group/group-spec :local {})])]
      (is (= 1 (count ss)))
      (is (= :local (:group-name (first ss))))
      (is (= (dissoc (localhost {:group-name :local}) :compute-service)
             (dissoc (:node (first ss)) :compute-service)))
      (is (every? :node ss)))))

(deftest all-group-nodes-test
  (testing "all-group-nodes"
    (let [service (make-localhost-compute :group-name :local)
          ss (group/all-group-nodes
              service
              [(group/group-spec :local {})]
              [(group/group-spec :other {})])]
      (is (= 1 (count ss)))
      (is (= :local (:group-name (first ss))))
      (is (= (dissoc (localhost {:group-name :local}) :compute-service)
             (dissoc (:node (first ss)) :compute-service))))))

(deftest node-count-adjuster-test
  (testing "node-count-adjuster"
    (let [session (session/create {:executor (ssh/ssh-executor)
                                   :plan-state (in-memory-plan-state)
                                   :user user/*admin-user*})
          service (make-localhost-compute :group-name :local)
          g (group/group-spec :local {:count 1})
          c (chan)
          targets (group/service-state
                   (compute/nodes service) [(group/group-spec :local {})])
          _ (is (every? :node targets))
          r (group/node-count-adjuster
             session
             service [g]
             targets
             c)
          [res e] (<!! c)]
      (is (map? res) "Result is a map")
      (is (= #{:targets :old-node-ids :results} (set (keys res)))
          "Result has the correct keys")
      (is (= 1 (count (:targets res))) "Result has a target")
      (is (empty? (:new-nodes res)) "Result has no new nodes")
      (is (empty? (:old-node-ids res)) "Result has no old nodes")
      (is (empty? (:results res)) "Has no phase results")
      (is (nil? e) "No exception thrown")
      (when e
        (print-cause-trace e)))))

(deftest converge*-test
  (testing "converge*"
    (let [session (session/create {:executor (ssh/ssh-executor)
                                   :plan-state (in-memory-plan-state)})
          service (make-localhost-compute :group-name :local)
          g (group/group-spec :local
              {:count 1
               :phases {:configure (plan-fn [session]
                                     (exec-script* session "ls"))}})
          c (chan)
          targets (group/service-state
                   (compute/nodes service) [(group/group-spec :local {})])
          _ (is (every? :node targets))
          r (group/converge* [g] c {:compute service})
          [res e] (<!! c)]
      (is (map? res) "Result is a map")
      (is (= #{:targets :old-node-ids :results} (set (keys res)))
          "Result has the correct keys")
      (is (empty? (:new-nodes res)) "Result has no new nodes")
      (is (empty? (:old-node-ids res)) "Result has no old nodes")
      (is (seq (:results res)) "Has phase results")
      (is (nil? e) "No exception thrown")
      (when e
        (print-cause-trace e)))))

(deftest converge-test
  (testing "converge"
    (let [service (make-localhost-compute :group-name :local)
          g (group/group-spec :local
              {:count 1
              :phases {:x (plan-fn [session] (exec-script* session "ls"))}})
          res (group/converge [g] :compute service)]
      (is (map? res) "Result is a map")
      (is (= #{:targets :old-node-ids :results} (set (keys res)))
          "Result has the correct keys")
      (is (empty? (:new-nodes res)) "Result has no new nodes")
      (is (empty? (:old-node-ids res)) "Result has no old nodes")
      (is (seq (:results res)) "Has phase results"))))



;; ;; test what happens when a script fails
;; (deftest lift-fail-test
;;   (with-source-line-comments true
;;     (with-location-info true
;;       (let [localhost (node-list/make-localhost-node :group-name "local")
;;             service (compute/instantiate-provider
;;                      :node-list :node-list [localhost])
;;             f (fn [x]
;;                 (exec-checked-script
;;                  "myscript"
;;                  (println ~x)
;;                  (file-exists? "abcdef")))]
;;         (testing "failed script"
;;           (let [log-out
;;                 (with-log-to-string []
;;                   (let [local (group-spec "local" :phases {:configure f})
;;                         result (lift
;;                                 local
;;                                 :user (assoc *admin-user*
;;                                         :username (test-username) :no-sudo true)
;;                                 :phase [[:configure "hello"]]
;;                                 :compute service
;;                                 :async true)
;;                         session @result]
;;                     (is (phase-errors @result))
;;                     (is (= 1 (count (phase-errors @result))))
;;                     (is (thrown? clojure.lang.ExceptionInfo
;;                                  (throw-phase-errors @result)))))]
;;             ;; TODO - find a way to re-enable this
;;             ;; I suspect it fails due to scope of *out* somewhere
;;             ;; (is (re-find
;;             ;;      #"ERROR pallet.execute - localhost #> myscript"
;;             ;;      log-out))
;;             ))))))




;; (deftest lift-all-node-set-test
;;   (let [local (group-spec
;;                   "local"
;;                 :phases {:configure (plan-fn (print-action "hello"))})
;;         localhost (node-list/make-localhost-node :group-name "local")
;;         service (compute/instantiate-provider
;;                  :node-list :node-list [localhost])]
;;     (testing "python"
;;       (let [session (lift
;;                      local
;;                      :user (assoc *admin-user*
;;                              :username (test-username) :no-sudo true)
;;                      :compute service)]
;;         (is (= ["hello\n"]
;;                (->>
;;                 session
;;                 :results
;;                 (filter
;;                  #(and (= "localhost" (hostname (-> % :target :node)))
;;                        (= :configure (:phase %))))
;;                 (mapcat :result)
;;                 (map :out))))))))
;; ;; this is in the wrong place really, as it is testing phase-fns with arguments
;; (deftest lift-arguments-test
;;   (let [localhost (node-list/make-localhost-node :group-name "local")
;;         service (compute/instantiate-provider
;;                  :node-list :node-list [localhost])]
;;     (testing "simple phase"
;;       (let [local (group-spec "local"
;;                     :phases {:configure (fn [x]
;;                                           (exec-script
;;                                            (println "xx" ~x "yy")))})
;;             session (lift
;;                      local
;;                      :user (assoc *admin-user*
;;                              :username (test-username) :no-sudo true)
;;                      :phase [[:configure "hello"]]
;;                      :compute service)]
;;         (is (= ["xx hello yy\n"]
;;                (->>
;;                 session
;;                 :results
;;                 (filter
;;                  #(and (= "localhost" (hostname (-> % :target :node)))
;;                        (= :configure (:phase %))))
;;                 (mapcat :result)
;;                 (map :out))))))
;;     (testing "compound phase"
;;       (let [server (server-spec
;;                     :phases {:configure (fn [x]
;;                                           (exec-script (println "xx" ~x)))})
;;             local (group-spec
;;                       "local"
;;                     :extends [server]
;;                     :phases {:configure (fn [x]
;;                                           (exec-script (println "yy" ~x)))})
;;             session (lift
;;                      local
;;                      :user (assoc *admin-user*
;;                              :username (test-username) :no-sudo true)
;;                      :phase [[:configure "hello"]]
;;                      :compute service)]
;;         (is (= ["xx hello\n" "yy hello\n"]
;;                (->>
;;                 session
;;                 :results
;;                 (filter
;;                  #(and (= "localhost" (hostname (-> % :target :node)))
;;                        (= :configure (:phase %))))
;;                 (mapcat :result)
;;                 (map :out))))))))






;; (use-fixtures :once (logging-threshold-fixture))

;; (deftest extend-specs-test
;;   (testing "simple ordering"
;;     (is (= [2 (add-session-verification-key {:v 3})]
;;            (with-session (add-session-verification-key {:v 1})
;;              [((-> (extend-specs
;;                     {:phases {:a (fn []
;;                                    (session! (update-in (session) [:v] inc))
;;                                    2)}}
;;                     [{:phases {:a (fn []
;;                                     (session! (update-in (session) [:v] * 2))
;;                                     1)}}])
;;                    :phases
;;                    :a))
;;               (session)]))))
;;   (testing "multiple extends"
;;     (is (= [3 (add-session-verification-key {:v 6})]
;;            (with-session (add-session-verification-key {:v 1})
;;              [((-> (extend-specs
;;                     {:phases {:a (fn []
;;                                    (session! (update-in (session) [:v] inc))
;;                                    3)}}
;;                     [{:phases {:a (fn []
;;                                     (session! (update-in (session) [:v] * 2))
;;                                     1)}}
;;                      {:phases {:a (fn []
;;                                     (session! (update-in (session) [:v] + 3))
;;                                     2)}}])
;;                    :phases
;;                    :a))
;;               (session)])))))

;; (deftest lift-test
;;   (testing "lift on group"
;;     (let [compute (make-localhost-compute)
;;           group (group-spec
;;                     (group-name (first (nodes compute)))
;;                   :phases {:p (plan-fn (exec-script "ls /"))})
;;           op (lift [group] :phase :p :compute compute)]
;;       (is op)
;;       (some
;;        (partial re-find #"/bin")
;;        (->> (mapcat :results op) (mapcat :out)))))
;;   (testing "lift on group async"
;;     (let [compute (make-localhost-compute)
;;           group (group-spec
;;                     (group-name (first (nodes compute)))
;;                   :phases {:p (plan-fn (exec-script "ls /"))})
;;           op (lift [group] :phase :p :compute compute :async true)]
;;       (is op)
;;       (is @op)
;;       (some
;;        (partial re-find #"/bin")
;;        (->> (mapcat :results @op) (mapcat :out)))))
;;   (testing "lift on group with explicit status-chan"
;;     (let [compute (make-localhost-compute)
;;           channel (chan)
;;           group (group-spec
;;                     (group-name (first (nodes compute)))
;;                   :phases {:p (plan-fn
;;                                (exec-script
;;                                 (println
;;                                  "lift on group with explicit status-chan")
;;                                 ("ls /")))})
;;           op (lift [group] :phase :p :compute compute
;;                    :status-chan channel :async true)
;;           t (timeout 10000)]
;;       (is op)
;;       (is (first (alts!! [channel t])))
;;       (loop [v (alts!! [channel t])]
;;         (when (first v) (recur (alts!! [channel t]))))
;;       (is @op)
;;       (some
;;        (partial re-find #"/bin")
;;        (->> (mapcat :results @op) (mapcat :out)))))
;;   (testing "lift on group with explicit operation"
;;     (let [compute (make-localhost-compute)
;;           operation (async-operation {})
;;           group (group-spec
;;                     (group-name (first (nodes compute)))
;;                   :phases {:p (plan-fn
;;                                (exec-script
;;                                 (println
;;                                  "lift on group with explicit operation")
;;                                 ("ls /")))})
;;           op (lift [group] :phase :p :compute compute
;;                    :operation operation :async true)]
;;       (is (= op operation))
;;       (is @op)
;;       (some
;;        (partial re-find #"/bin")
;;        (->> (mapcat :results @op) (mapcat :out)))))
;;   (testing "lift on group with inline plan-fn"
;;     (let [compute (make-localhost-compute)
;;           group (group-spec (group-name (first (nodes compute))))
;;           op (lift [group]
;;                    :phase (plan-fn (exec-script "ls /"))
;;                    :compute compute)]
;;       (is op)
;;       (some
;;        (partial re-find #"/bin")
;;        (->> (mapcat :results op) (mapcat :out))))))

;; (deftest converge-test
;;   (testing "converge on node-list"
;;     (let [compute (node-list-service [])
;;           group (group-spec "spec")
;;           op (converge {group 1} :compute compute)]
;;       (is op)
;;       (is (empty? (:new-nodes op))))))

;; (deftest lift-with-environment-test
;;   (testing "lift with environment"
;;     (let [compute (make-localhost-compute)
;;           group (group-spec (group-name (first (nodes compute))))
;;           a (atom nil)
;;           op (lift [group]
;;                    :phase (plan-fn
;;                             (let [k (get-environment [:my-key])]
;;                               (reset! a k)))
;;                    :compute compute
;;                    :environment {:my-key 1})]
;;       (is op)
;;       (is (= 1 @a)))))

;; (deftest make-user-test
;;   (let [username "userfred"
;;         password "pw"
;;         private-key-path "pri"
;;         public-key-path "pub"
;;         passphrase "key-passphrase"]
;;     (is (= {:username username
;;             :password password
;;             :private-key-path private-key-path
;;             :public-key-path public-key-path
;;             :private-key nil
;;             :public-key nil
;;             :passphrase passphrase
;;             :sudo-password password
;;             :no-sudo nil
;;             :sudo-user nil}
;;            (into {} (make-user username
;;                                :password password
;;                                :private-key-path private-key-path
;;                                :public-key-path public-key-path
;;                                :passphrase passphrase))))
;;     (is (= {:username username
;;             :password nil
;;             :private-key-path (default-private-key-path)
;;             :public-key-path (default-public-key-path)
;;             :private-key nil
;;             :public-key nil
;;             :passphrase nil
;;             :sudo-password nil
;;             :no-sudo nil
;;             :sudo-user nil}
;;            (into {} (make-user username))))
;;     (is (= {:username username
;;             :password nil
;;             :private-key-path (default-private-key-path)
;;             :public-key-path (default-public-key-path)
;;             :private-key nil
;;             :public-key nil
;;             :passphrase nil
;;             :sudo-password password
;;             :no-sudo nil
;;             :sudo-user nil}
;;            (into {} (make-user username :sudo-password password))))
;;     (is (= {:username username
;;             :password nil
;;             :private-key-path (default-private-key-path)
;;             :public-key-path (default-public-key-path)
;;             :private-key nil
;;             :public-key nil
;;             :passphrase nil
;;             :sudo-password nil
;;             :no-sudo true
;;             :sudo-user nil}
;;            (into {} (make-user username :no-sudo true))))
;;     (is (= {:username username
;;             :password nil
;;             :private-key-path (default-private-key-path)
;;             :public-key-path (default-public-key-path)
;;             :private-key nil
;;             :public-key nil
;;             :passphrase nil
;;             :sudo-password nil
;;             :no-sudo nil
;;             :sudo-user "fred"}
;;            (into {} (make-user username :sudo-user "fred"))))))

;; (deftest node-spec-test
;;   (is (= {:image {}}
;;          (node-spec :image {})))
;;   (is (= {:hardware {}}
;;          (node-spec :hardware {})))
;;   (is (= {:location {:subnet-id "subnet-xxxx"}}
;;          (node-spec :location {:subnet-id "subnet-xxxx"})))
;;   (is (= {:hardware {:hardware-model "xxxx"}}
;;          (node-spec :hardware {:hardware-model "xxxx"})))
;;   (testing "type"
;;     (is (= :pallet.api/node-spec (type (node-spec :hardware {}))))))

;; (deftest server-spec-test
;;   (let [f (fn [] :f)]
;;     (is (= {:phases {:a f} :default-phases [:configure]}
;;            (server-spec :phases {:a f})))
;;     (testing "phases-meta"
;;       (let [spec (server-spec :phases {:a f}
;;                               :phases-meta {:a {:phase-execution-f f}})]
;;         (is (= :f ((-> spec :phases :a))))
;;         (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
;;     (testing "phases-meta extension"
;;       (let [spec1 (server-spec :phases {:a f}
;;                               :phases-meta {:a {:phase-execution-f f}})
;;             spec2 (server-spec :phases {:a #()})
;;             spec (server-spec :extends [spec1 spec2])]
;;         (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
;;     (testing "default phases-meta"
;;       (let [spec (server-spec :phases {:bootstrap f})]
;;         (is (= (:bootstrap default-phase-meta)
;;                (-> spec :phases :bootstrap meta)))))
;;     (is (= {:phases {:a f} :image {:image-id "2"} :default-phases [:configure]}
;;            (server-spec
;;             :phases {:a f} :node-spec (node-spec :image {:image-id "2"})))
;;         "node-spec merged in")
;;     (is (= {:phases {:a f} :image {:image-id "2"}
;;             :hardware {:hardware-id "id"}
;;             :default-phases [:configure]}
;;            (server-spec
;;             :phases {:a f}
;;             :node-spec (node-spec :image {:image-id "2"})
;;             :hardware {:hardware-id "id"}))
;;         "node-spec keys moved to :node-spec keyword")
;;     (is (= {:phases {:a f} :image {:image-id "2"} :default-phases [:configure]}
;;            (server-spec
;;             :extends (server-spec
;;                       :phases {:a f} :node-spec {:image {:image-id "2"}})))
;;         "extends a server-spec"))
;;   (is (= {:roles #{:r1} :default-phases [:configure]}
;;          (server-spec :roles :r1)) "Allow roles as keyword")
;;   (is (= {:roles #{:r1} :default-phases [:configure]}
;;          (server-spec :roles [:r1])) "Allow roles as sequence")
;;   (testing "type"
;;     (is (= :pallet.api/server-spec (type (server-spec :roles :r1))))))

;; (deftest group-spec-test
;;   (let [f (fn [])]
;;     (is (= {:group-name :gn :phases {:a f} :default-phases [:configure]}
;;            (dissoc
;;             (group-spec "gn" :extends (server-spec :phases {:a f}))
;;             :node-filter)))
;;     (is (= {:group-name :gn :phases {:a f} :image {:image-id "2"}
;;             :default-phases [:configure]}
;;            (dissoc
;;             (group-spec
;;                 "gn"
;;               :extends [(server-spec :phases {:a f})
;;                         (server-spec :node-spec {:image {:image-id "2"}})])
;;             :node-filter)))
;;     (is (= {:group-name :gn :phases {:a f}
;;             :image {:image-id "2"} :roles #{:r1 :r2 :r3}
;;             :default-phases [:configure]}
;;            (dissoc
;;             (group-spec
;;                 "gn"
;;               :roles :r1
;;               :extends [(server-spec :phases {:a f} :roles :r2)
;;                         (server-spec
;;                          :node-spec {:image {:image-id "2"}} :roles [:r3])])
;;             :node-filter))))
;;   (testing "type"
;;     (is (= :pallet.api/group-spec (type (group-spec "gn")))))
;;   (testing "default-phases"
;;     (testing "default"
;;       (is (= [:configure] (:default-phases (group-spec "gn")))))
;;     (testing "merging"
;;       (is (= [:install :configure :test]
;;              (:default-phases
;;               (group-spec "gn"
;;                 :extends [(server-spec :default-phases [:configure])
;;                           (server-spec :default-phases [:install :configure])
;;                           (server-spec :default-phases [:test])])))))
;;     (testing "explicit override"
;;       (is (= [:install :configure]
;;              (:default-phases
;;               (group-spec "gn"
;;                 :extends [(server-spec :default-phases [:configure])
;;                           (server-spec :default-phases [:install :configure])
;;                           (server-spec :default-phases [:test])]
;;                 :default-phases [:install :configure])))))))

;; (deftest cluster-spec-test
;;   (let [x (fn [x] (update-in x [:x] inc))
;;         gn (group-spec "gn" :count 1 :phases {:x (fn [] )})
;;         go (group-spec "go" :count 2 :phases {:o (fn [] )})
;;         cluster (cluster-spec
;;                  "cl"
;;                  :phases {:x x}
;;                  :groups [gn go]
;;                  :node-spec {:image {:os-family :ubuntu}})]
;;     (is (= 2 (count (:groups cluster))))
;;     (testing "names are prefixed"
;;       (is (= :cl-gn (:group-name (first (:groups cluster)))))
;;       (is (= :cl-go (:group-name (second (:groups cluster))))))
;;     (testing "type"
;;       (is (= :pallet.api/cluster-spec (type cluster))))))

;; (deftest group-nodes-test
;;   (let [compute (make-localhost-compute)
;;         g (group-spec "local")
;;         service-spec (group-nodes compute [g])]
;;     (is (= 1 (count service-spec)))))
