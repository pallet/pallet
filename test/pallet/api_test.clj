(ns pallet.api-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-script]]
   [pallet.api
    :refer [cluster-spec
            converge
            extend-specs
            group-nodes
            group-spec
            lift
            make-user
            node-spec
            plan-fn
            server-spec]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :refer [nodes]]
   [pallet.compute.node-list :refer [node-list-service]]
   [pallet.core.primitives :refer [default-phase-meta]]
   [pallet.core.session :refer [session session! with-session]]
   [pallet.core.user :refer [default-private-key-path default-public-key-path]]
   [pallet.environment :refer [get-environment]]
   [pallet.node :refer [group-name]]
   [pallet.session.verify :refer [add-session-verification-key]]
   [pallet.test-utils :refer [make-localhost-compute]]))

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

(deftest converge-test
  (testing "converge on node-list"
    (let [compute (node-list-service [])
          group (group-spec "spec")
          op (converge {group 1} :compute compute)]
      (is op)
      (is (empty? (:new-nodes op))))))

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
  (is (= {:location {:subnet-id "subnet-xxxx"}}
         (node-spec :location {:subnet-id "subnet-xxxx"})))
  (is (= {:hardware {:hardware-model "xxxx"}}
         (node-spec :hardware {:hardware-model "xxxx"})))
  (testing "type"
    (is (= :pallet.api/node-spec (type (node-spec :hardware {}))))))

(deftest server-spec-test
  (let [f (fn [] :f)]
    (is (= {:phases {:a f} :default-phases [:configure]}
           (server-spec :phases {:a f})))
    (testing "phases-meta"
      (let [spec (server-spec :phases {:a f}
                              :phases-meta {:a {:phase-execution-f f}})]
        (is (= :f ((-> spec :phases :a))))
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "phases-meta extension"
      (let [spec1 (server-spec :phases {:a f}
                              :phases-meta {:a {:phase-execution-f f}})
            spec2 (server-spec :phases {:a #()})
            spec (server-spec :extends [spec1 spec2])]
        (is (= {:phase-execution-f f} (-> spec :phases :a meta)))))
    (testing "default phases-meta"
      (let [spec (server-spec :phases {:bootstrap f})]
        (is (= (:bootstrap default-phase-meta)
               (-> spec :phases :bootstrap meta)))))
    (is (= {:phases {:a f} :image {:image-id "2"} :default-phases [:configure]}
           (server-spec
            :phases {:a f} :node-spec (node-spec :image {:image-id "2"})))
        "node-spec merged in")
    (is (= {:phases {:a f} :image {:image-id "2"}
            :hardware {:hardware-id "id"}
            :default-phases [:configure]}
           (server-spec
            :phases {:a f}
            :node-spec (node-spec :image {:image-id "2"})
            :hardware {:hardware-id "id"}))
        "node-spec keys moved to :node-spec keyword")
    (is (= {:phases {:a f} :image {:image-id "2"} :default-phases [:configure]}
           (server-spec
            :extends (server-spec
                      :phases {:a f} :node-spec {:image {:image-id "2"}})))
        "extends a server-spec"))
  (is (= {:roles #{:r1} :default-phases [:configure]}
         (server-spec :roles :r1)) "Allow roles as keyword")
  (is (= {:roles #{:r1} :default-phases [:configure]}
         (server-spec :roles [:r1])) "Allow roles as sequence")
  (testing "type"
    (is (= :pallet.api/server-spec (type (server-spec :roles :r1))))))

(deftest group-spec-test
  (let [f (fn [])]
    (is (= {:group-name :gn :phases {:a f} :default-phases [:configure]}
           (dissoc
            (group-spec "gn" :extends (server-spec :phases {:a f}))
            :node-filter)))
    (is (= {:group-name :gn :phases {:a f} :image {:image-id "2"}
            :default-phases [:configure]}
           (dissoc
            (group-spec
                "gn"
              :extends [(server-spec :phases {:a f})
                        (server-spec :node-spec {:image {:image-id "2"}})])
            :node-filter)))
    (is (= {:group-name :gn :phases {:a f}
            :image {:image-id "2"} :roles #{:r1 :r2 :r3}
            :default-phases [:configure]}
           (dissoc
            (group-spec
                "gn"
              :roles :r1
              :extends [(server-spec :phases {:a f} :roles :r2)
                        (server-spec
                         :node-spec {:image {:image-id "2"}} :roles [:r3])])
            :node-filter))))
  (testing "type"
    (is (= :pallet.api/group-spec (type (group-spec "gn")))))
  (testing "default-phases"
    (testing "default"
      (is (= [:configure] (:default-phases (group-spec "gn")))))
    (testing "merging"
      (is (= [:install :configure :test]
             (:default-phases
              (group-spec "gn"
                :extends [(server-spec :default-phases [:configure])
                          (server-spec :default-phases [:install :configure])
                          (server-spec :default-phases [:test])])))))
    (testing "explicit override"
      (is (= [:install :configure]
             (:default-phases
              (group-spec "gn"
                :extends [(server-spec :default-phases [:configure])
                          (server-spec :default-phases [:install :configure])
                          (server-spec :default-phases [:test])]
                :default-phases [:install :configure])))))))

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

(deftest group-nodes-test
  (let [compute (make-localhost-compute)
        g (group-spec "local")
        service-spec (group-nodes compute [g])]
    (is (= 1 (count service-spec)))))
