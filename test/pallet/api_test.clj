(ns pallet.api-test
  (:use
   clojure.test
   pallet.api
   [pallet.actions :only [exec-script]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.compute :only [nodes]]
   [pallet.core.session :only [session session! with-session]]
   [pallet.core.user :only [default-private-key-path default-public-key-path]]
   [pallet.environment :only [get-environment]]
   [pallet.node :only [group-name]]
   [pallet.session.verify :only [add-session-verification-key]]
   [pallet.test-utils :only [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest extend-specs-test
  (testing "simple ordering"
    (is (= [2 (add-session-verification-key {:v 3})]
           (with-session (add-session-verification-key {:v 1})
             [((-> (extend-specs
                    {:phases {:a (fn []
                                   (session! (update-in (session) [:v] inc))
                                   2)}}
                    [{:phases {:a (fn []
                                    (session! (update-in (session) [:v] * 2))
                                    1)}}])
                   :phases
                   :a))
              (session)]))))
  (testing "multiple extends"
    (is (= [3 (add-session-verification-key {:v 6})]
           (with-session (add-session-verification-key {:v 1})
             [((-> (extend-specs
                    {:phases {:a (fn []
                                   (session! (update-in (session) [:v] inc))
                                   3)}}
                    [{:phases {:a (fn []
                                    (session! (update-in (session) [:v] * 2))
                                    1)}}
                     {:phases {:a (fn []
                                    (session! (update-in (session) [:v] + 3))
                                    2)}}])
                   :phases
                   :a))
              (session)])))))

(deftest lift-test
  (testing "lift on group"
    (let [compute (make-localhost-compute)
          group (group-spec
                    (group-name (first (nodes compute)))
                  :phases {:p (plan-fn (exec-script "ls /"))})
          op (lift [group] :phase :p :compute compute)]
      (is op)
      (some
       (partial re-find #"/bin")
       (->> (mapcat :results op) (mapcat :out)))))
  (testing "lift on group async"
    (let [compute (make-localhost-compute)
          group (group-spec
                    (group-name (first (nodes compute)))
                  :phases {:p (plan-fn (exec-script "ls /"))})
          op (lift [group] :phase :p :compute compute :async true)]
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
      (is op)
      (some
       (partial re-find #"/bin")
       (->> (mapcat :results op) (mapcat :out))))))

(deftest lift-with-environment-test
  (testing "lift with environment"
    (let [compute (make-localhost-compute)
          group (group-spec (group-name (first (nodes compute))))
          a (atom nil)
          op (lift [group]
                   :phase (plan-fn
                            (let [k (get-environment [:my-key])]
                              (reset! a k)))
                   :compute compute
                   :environment {:my-key 1})]
      (is op)
      (is (= 1 @a)))))

(deftest make-user-test
  (let [username "userfred"
        password "pw"
        private-key-path "pri"
        public-key-path "pub"
        passphrase "key-passphrase"]
    (is (= {:username username
            :password password
            :private-key-path private-key-path
            :public-key-path public-key-path
            :private-key nil
            :public-key nil
            :passphrase passphrase
            :sudo-password password
            :no-sudo nil
            :sudo-user nil}
           (into {} (make-user username
                               :password password
                               :private-key-path private-key-path
                               :public-key-path public-key-path
                               :passphrase passphrase))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :private-key nil
            :public-key nil
            :passphrase nil
            :sudo-password nil
            :no-sudo nil
            :sudo-user nil}
           (into {} (make-user username))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :private-key nil
            :public-key nil
            :passphrase nil
            :sudo-password password
            :no-sudo nil
            :sudo-user nil}
           (into {} (make-user username :sudo-password password))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :private-key nil
            :public-key nil
            :passphrase nil
            :sudo-password nil
            :no-sudo true
            :sudo-user nil}
           (into {} (make-user username :no-sudo true))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :private-key nil
            :public-key nil
            :passphrase nil
            :sudo-password nil
            :no-sudo nil
            :sudo-user "fred"}
           (into {} (make-user username :sudo-user "fred"))))))

(deftest node-spec-test
  (is (= {:image {}}
         (node-spec :image {})))
  (is (= {:hardware {}}
         (node-spec :hardware {})))
  (testing "type"
    (is (= :pallet.api/node-spec (type (node-spec :hardware {}))))))

(deftest server-spec-test
  (is (= {:phases {:a 1}}
         (server-spec :phases {:a 1})))
  (is (= {:phases {:a 1} :image {:image-id "2"}}
         (server-spec
          :phases {:a 1} :node-spec (node-spec :image {:image-id "2"})))
      "node-spec merged in")
  (is (= {:phases {:a 1} :image {:image-id "2"}
          :hardware {:hardware-id "id"}}
         (server-spec
          :phases {:a 1}
          :node-spec (node-spec :image {:image-id "2"})
          :hardware {:hardware-id "id"}))
      "node-spec keys moved to :node-spec keyword")
  (is (= {:phases {:a 1} :image {:image-id "2"}}
         (server-spec
          :extends (server-spec
                    :phases {:a 1} :node-spec {:image {:image-id "2"}})))
      "extends a server-spec")
  (is (= {:roles #{:r1}} (server-spec :roles :r1)) "Allow roles as keyword")
  (is (= {:roles #{:r1}} (server-spec :roles [:r1])) "Allow roles as sequence")
  (testing "type"
    (is (= :pallet.api/server-spec (type (server-spec :roles :r1))))))

(deftest group-spec-test
  (is (= {:group-name :gn :phases {:a 1}}
         (dissoc
          (group-spec "gn" :extends (server-spec :phases {:a 1}))
          :node-filter)))
  (is (:node-filter
       (group-spec "gn" :extends (server-spec :phases {:a 1}))))
  (is (= {:group-name :gn :phases {:a 1} :image {:image-id "2"}}
         (dissoc
          (group-spec
              "gn"
            :extends [(server-spec :phases {:a 1})
                      (server-spec :node-spec {:image {:image-id "2"}})])
          :node-filter)))
  (is (= {:group-name :gn :phases {:a 1}
          :image {:image-id "2"} :roles #{:r1 :r2 :r3}}
         (dissoc
          (group-spec
              "gn"
            :roles :r1
            :extends [(server-spec :phases {:a 1} :roles :r2)
                      (server-spec
                       :node-spec {:image {:image-id "2"}} :roles [:r3])])
          :node-filter)))
  (testing "type"
    (is (= :pallet.api/group-spec (type (group-spec "gn"))))))

(deftest cluster-spec-test
  (let [x (fn [x] (update-in x [:x] inc))
        gn (group-spec "gn" :count 1 :phases {:x (fn [] )})
        go (group-spec "go" :count 2 :phases {:o (fn [] )})
        cluster (cluster-spec
                 "cl"
                 :phases {:x x}
                 :groups [gn go]
                 :node-spec {:image {:os-family :ubuntu}})]
    (is (= 2 (count (:groups cluster))))
    (testing "names are prefixed"
      (is (= :cl-gn (:group-name (first (:groups cluster)))))
      (is (= :cl-go (:group-name (second (:groups cluster))))))
    (testing "type"
      (is (= :pallet.api/cluster-spec (type cluster))))))
