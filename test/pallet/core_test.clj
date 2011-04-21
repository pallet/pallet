(ns pallet.core-test
  (:use pallet.core)
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.argument :as argument]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.log4j :as log4j]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core :as core]
   [pallet.mock :as mock]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.string :as string])
  (:use
   clojure.test))

(use-fixtures :once (log4j/logging-threshold-fixture))

;; tests run with node-list, as no external dependencies

;; Allow running against other compute services if required
(deftest with-admin-user-test
  (let [x (rand)]
    (with-admin-user [x]
      (is (= x (:username pallet.utils/*admin-user*))))))

;; this test doesn't work too well if the test are run in more than
;; one thread...
#_
(deftest admin-user-test
  (let [username "userfred"
        old pallet.utils/*admin-user*]
    (admin-user username)
    (is (map? pallet.utils/*admin-user*))
    (is (= username (:username pallet.utils/*admin-user*)))
    (is (= (pallet.utils/default-public-key-path)
           (:public-key-path pallet.utils/*admin-user*)))
    (is (= (pallet.utils/default-private-key-path)
           (:private-key-path pallet.utils/*admin-user*)))
    (is (nil? (:password pallet.utils/*admin-user*)))

    (admin-user username :password "pw" :public-key-path "pub"
                :private-key-path "pri")
    (is (map? pallet.utils/*admin-user*))
    (is (= username (:username pallet.utils/*admin-user*)))
    (is (= "pub" (:public-key-path pallet.utils/*admin-user*)))
    (is (= "pri" (:private-key-path pallet.utils/*admin-user*)))
    (is (= "pw" (:password pallet.utils/*admin-user*)))

    (admin-user old)
    (is (= old pallet.utils/*admin-user*))))

(def ubuntu-node (node-spec :image {:os-family :ubuntu}))

(deftest group-with-prefix-test
  (is (= {:group-name :pa}
         (#'core/group-with-prefix "p" (test-utils/group :a)))))

(deftest node-map-with-prefix-test
  (is (= {{:group-name :pa} 1}
         (#'core/node-map-with-prefix "p" {(test-utils/group :a) 1}))))

(deftest node-count-difference-test
  (is (= {:a 1 :b -1}
         (#'core/node-count-difference
          [(test-utils/group :a :count 2 :servers [:a-server])
           (test-utils/group :b :count 0 :servers [:a-server])])))
  (is (= {:a 1 :b 1}
         (#'core/node-count-difference
          [(test-utils/group :a :count 1) (test-utils/group :b :count 1)]))))

(deftest converge-node-counts-test
  (let [a (group-spec "a" :node-spec ubuntu-node)
        a-node (test-utils/make-node "a" :running true)
        compute (compute/compute-service "node-list" :node-list [a-node])]
    (#'core/converge-node-counts
     {:groups [{:group-name :a :count 1 :servers [{:node a-node}]}]
      :environment
      {:compute compute
       :algorithms {:lift-fn sequential-lift
                    :converge-fn
                    (var-get #'core/serial-adjust-node-counts)}}})))

(deftest group-spec?-test
  (is (#'core/group-spec? (core/group-spec "a")))
  (is (#'core/group-spec? (core/make-node "a" (server-spec)))))

(deftest nodes-in-set-test
  (let [a (group-spec :a :node-spec ubuntu-node)
        b (group-spec :b :node-spec ubuntu-node)
        a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")]
    (testing "sequence of groups"
      (let []
        (is (= {a #{a-node} b #{b-node}}
               (#'core/nodes-in-set [a b] nil [a-node b-node])))))
    (testing "explicit nodes"
      (is (= {a #{a-node}}
             (#'core/nodes-in-set {a a-node} nil nil)))
      (is (= {a #{a-node b-node}}
             (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
      (is (= {a #{a-node} b #{b-node}}
             (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
    (let [pa (group-spec :pa :node-spec ubuntu-node)
          pb (group-spec :pb :node-spec ubuntu-node)]
      (is (= {pa #{a-node}}
             (#'core/nodes-in-set {a a-node} "p" nil)))
      (is (= {pa #{a-node b-node}}
             (#'core/nodes-in-set {a #{a-node b-node}} "p" nil)))
      (is (= {pa #{a-node} pb #{b-node}}
             (#'core/nodes-in-set {a #{a-node} b #{b-node}} "p" nil)))
      (is (= {pa #{a-node} pb #{b-node}}
             (#'core/nodes-in-set {a a-node b b-node} "p" nil))))))

(deftest node-in-types?-test
  (let [a (group-spec :a)
        b (group-spec :b)]
    (is (#'core/node-in-types? [a b] (test-utils/make-node "a")))
    (is (not (#'core/node-in-types? [a b] (test-utils/make-node "c"))))))

(deftest nodes-for-group-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")]
    (is (= [nb] (#'core/nodes-for-group [na nb nc] b)))
    (is (= [na] (#'core/nodes-for-group [na nc] a)))))

(deftest group-spec-with-count-test
  (let [a (group-spec "a")
        b (group-spec "b")]
    (is (= [(assoc a :count 1) (assoc b :count 2)]
             (map #'core/group-spec-with-count {a 1 b 2})))))


(deftest server-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= {:node-id :id
            :group-name :a
            :packager :aptitude
            :image {:os-version "v"
                    :os-family :ubuntu}
            :node n}
           (server a n {})))))

(deftest groups-with-servers-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= [{:servers [{:node-id :id
                            :group-name :a
                            :packager :aptitude
                            :image {:os-version "v"
                                    :os-family :ubuntu}
                            :node n}]
             :group-name :a
             :image {}}]
             (groups-with-servers {a #{n}})))
    (testing "with options"
      (is (= [{:servers [{:node-id :id
                              :group-name :a
                              :packager :aptitude
                              :image {:os-version "v"
                                      :os-family :ubuntu}
                              :node n
                              :extra 1}]
               :group-name :a
               :image {}}]
               (groups-with-servers {a #{n}} :extra 1))))))

(deftest session-with-groups-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= {:groups [{:servers [{:node-id :id
                                     :group-name :a
                                     :packager :aptitude
                                     :image {:os-version "v"
                                             :os-family :ubuntu}
                                     :node n}]
                      :group-name :a
                      :image {}}]
            :all-nodes [n]
            :node-set {a #{n}}}
           (session-with-groups
             {:all-nodes [n] :node-set {a #{n}}})))
    (testing "with-options"
      (is (= {:groups [{:servers [{:node-id :id
                                       :group-name :a
                                       :packager :aptitude
                                       :image {:os-version "v"
                                               :os-family :ubuntu}
                                       :node n
                                       :invoke-only true}]
                        :group-name :a
                        :image {}}]
              :all-nodes [n]
              :node-set nil
              :all-node-set {a #{n}}}
             (session-with-groups
               {:all-nodes [n] :node-set nil :all-node-set {a #{n}}}))))))


(deftest session-with-environment-test
  (binding [pallet.core/*middleware* :middleware]
    (testing "defaults"
      (is (= {:executor core/default-executors
              :environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware :middleware
               :algorithms {:lift-fn core/parallel-lift
                            :converge-fn core/parallel-adjust-node-counts}}}
             (#'core/session-with-environment {}))))
    (testing "passing a prefix"
      (is (= {:executor core/default-executors
              :environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware *middleware*
               :algorithms {:lift-fn core/parallel-lift
                            :converge-fn core/parallel-adjust-node-counts}}
              :prefix "prefix"}
             (#'core/session-with-environment {:prefix "prefix"}))))
    (testing "passing a user"
      (let [user (utils/make-user "fred")]
        (is (= {:executor core/default-executors
                :environment
                {:blobstore nil :compute nil  :user user
                 :middleware :middleware
                 :algorithms {:lift-fn parallel-lift
                              :converge-fn parallel-adjust-node-counts}}}
               (#'core/session-with-environment {:user user})))))))

(deftest node-spec-test
  (is (= {:image {}}
         (node-spec :image {})))
  (is (= {:hardware {}}
         (node-spec :hardware {}))))

(deftest server-spec-test
  (is (= {:phases {:a 1}}
         (server-spec :phases {:a 1})))
  (is (= {:phases {:a 1} :image {:b 2}}
         (server-spec :phases {:a 1} :node-spec (node-spec :image {:b 2})))
      "node-spec merged in")
  (is (= {:phases {:a 1} :image {:b 2} :hardware {:hardware-id :id}}
         (server-spec
          :phases {:a 1}
          :node-spec (node-spec :image {:b 2})
          :hardware {:hardware-id :id}))
      "node-spec keys moved to :node-spec keyword")
  (is (= {:phases {:a 1} :image {:b 2}}
         (server-spec
          :extends (server-spec :phases {:a 1} :node-spec {:image {:b 2}})))
      "extends a server-spec"))

(deftest group-spec-test
  (is (= {:group-name :gn :phases {:a 1}}
         (group-spec "gn" :extends (server-spec :phases {:a 1}))))
  (is (= {:group-name :gn :phases {:a 1} :image {:b 2}}
         (group-spec
          "gn"
          :extends [(server-spec :phases {:a 1})
                    (server-spec :node-spec {:image {:b 2}})]))))

(deftest make-node-test
  (is (= {:group-name :fred :image {:os-family :ubuntu}}
         (make-node "fred" {:os-family :ubuntu})))
  (is (= {:group-name :tom :image {:os-family :centos}}
         (make-node "tom" {:os-family :centos}))))

(deftest defnode-test
  (defnode fred {:os-family :ubuntu})
  (is (= {:group-name :fred :image {:os-family :ubuntu}} fred))
  (defnode tom "This is tom" {:os-family :centos})
  (is (= {:group-name :tom :image {:os-family :centos}} tom))
  (is (= "This is tom" (:doc (meta #'tom))))
  (defnode harry (tom :image))
  (is (= {:group-name :harry :image {:os-family :centos}} harry))
  (let [test-component (action/bash-action [session arg] (str arg))
        node-with-phases (make-node
                          "node-with-phases" (tom :image)
                          :bootstrap #(test-component % :a)
                          :configure  #(test-component % :b))]
    (is (= #{:bootstrap :configure} (set (keys (node-with-phases :phases)))))
    (let [session {:server {:node-id :id
                            :group-name :group-name
                            :packager :yum
                            :image {}
                            :node (test-utils/make-node "group-name" :id "id")
                            :phases (:phases node-with-phases)}}]
      (is (= ":a\n"
             (first
              (build-actions/produce-phases
               (assoc session :phase :bootstrap)))))
      (is (= ":b\n"
             (first
              (build-actions/produce-phases
               (assoc session :phase :configure))))))))

(def identity-action (action/bash-action [session x] x))
(def identity-local-action (action/clj-action [session] session))

(deftest bootstrap-script-test
  (is (= "a\n"
         (#'core/bootstrap-script
          {:group {:image {:os-family :ubuntu}
                   :packager :aptitude
                   :phases {:bootstrap (phase/phase-fn
                                        (identity-action "a"))}}})))
  (testing "rejects local actions"
    (is (thrown-with-msg?
          clojure.contrib.condition.Condition
          #"local actions"
          (#'core/bootstrap-script
           {:group
            {:image {:os-family :ubuntu}
             :packager :aptitude
             :phases {:bootstrap (phase/phase-fn
                                  (identity-local-action))}}}))))
  (testing "requires a packager"
    (is (thrown?
         java.lang.AssertionError
         (#'core/bootstrap-script
          {:group {:image {:os-family :ubuntu}}}))))
  (testing "requires an os-family"
    (is (thrown?
         java.lang.AssertionError
         (#'core/bootstrap-script
          {:group {:packager :yum}})))))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(action/clj-action
       [session]
       (clojure.contrib.logging/info (format "Seenfn %s" name))
       (testing (format "not already seen %s" name)
         (is (not @seen)))
       (reset! seen true)
       (is (:server session))
       (is (:group session))
       session)
      seen?]))

(deftest warn-on-undefined-phase-test
  (testing "return value"
    (is (= {:a 1} (#'core/warn-on-undefined-phase {:a 1}))))
  (test-utils/logging-to-stdout
   (is (= "Undefined phases: a, b\n"
          (with-out-str
            (#'core/warn-on-undefined-phase
             {:groups nil :phase-list [:a :b]})))))
  (test-utils/logging-to-stdout
   (is (= "Undefined phases: b\n"
          (with-out-str
            (#'core/warn-on-undefined-phase
             {:groups [{:phases {:a identity}}]
              :phase-list [:a :b]}))))))

(deftest identify-anonymous-phases-test
  (testing "with keyword"
    (is (= {:phase-list [:a]}
           (#'core/identify-anonymous-phases {:phase-list [:a]}))))
  (testing "with non-keyword"
    (let [session (#'core/identify-anonymous-phases {:phase-list ['a]})]
      (is (every? keyword (:phase-list session)))
      (is (= 'a
             (get (:inline-phases session) (first (:phase-list session))))))))

(deftest session-with-default-phase-test
  (testing "with empty phase list"
    (is (= {:phase-list [:configure]}
           (#'core/session-with-default-phase {}))))
  (testing "with non-empty phase list"
    (is (= {:phase-list [:a]}
           (#'core/session-with-default-phase {:phase-list [:a]})))))

(deftest session-with-configure-phase-test
  (testing "with empty phase-list"
    (is (= {:phase-list [:configure]}
           (#'core/session-with-configure-phase {}))))
  (testing "with phase list without configure"
    (is (= {:phase-list [:configure :a]}
           (#'core/session-with-configure-phase {:phase-list [:a]}))))
  (testing "with phase list with configure"
    (is (= {:phase-list [:a :configure]}
           (#'core/session-with-configure-phase
             {:phase-list [:a :configure]})))))

(deftest lift-test
  (testing "node-list"
    (let [local (group-spec "local")
          [localf seen?] (seen-fn "lift-test")
          localhost (node-list/make-localhost-node :group-name "local")
          service (compute/compute-service "node-list" :node-list [localhost])]
      (is (re-find
           #"bin"
           (->
            (lift
             local
             :phase [(phase/phase-fn (exec-script/exec-script (~lib/ls "/")))
                     (phase/phase-fn (localf))]
             :user (assoc utils/*admin-user*
                     :username (test-utils/test-username)
                     :no-sudo true)
             :compute service)
             :results :localhost pr-str)))
      (is (seen?))
      (testing "invalid :phases keyword"
        (is (thrown-with-msg?
              clojure.contrib.condition.Condition
              #":phases"
              (lift local :phases []))))
      (testing "invalid keyword"
        (is (thrown-with-msg?
              clojure.contrib.condition.Condition
              #"Invalid"
              (lift local :abcdef [])))))))

(deftest lift-parallel-test
  (defnode local {})
  (testing "node-list"
    (let [[localf seen?] (seen-fn "lift-parallel-test")
          service (compute/compute-service
                   "node-list"
                   :node-list [(node-list/make-localhost-node :tag "local")])]
      (is (re-find
           #"bin"
           (->
             (lift local
                   :phase [(phase/phase-fn (exec-script/exec-script (ls "/")))
                           (phase/phase-fn (localf))]
                   :user (assoc utils/*admin-user*
                           :username (test-utils/test-username)
                           :no-sudo true)
                   :compute service
                   :environment
                   {:algorithms {:lift-fn #'pallet.core/parallel-lift}})
             :results :localhost pr-str)))
      (is (seen?))
      (testing "invalid :phases keyword"
        (is (thrown-with-msg?
              clojure.contrib.condition.Condition
              #":phases"
              (lift local :phases []))))
      (testing "invalid keyword"
        (is (thrown-with-msg?
              clojure.contrib.condition.Condition
              #"Invalid"
              (lift local :abcdef [])))))))

(deftest lift2-test
  (let [[localf seen?] (seen-fn "x")
        [localfy seeny?] (seen-fn "y")
        compute (compute/compute-service
                 "node-list"
                 :node-list [(node-list/make-localhost-node
                              :group-name "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :group-name "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (make-node "x1" {} :configure (phase/phase-fn localf))
        y1 (make-node "y1" {} :configure (phase/phase-fn localfy))]
    (is (map?
         (lift [x1 y1]
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute compute)))
    (is (seen?))
    (is (seeny?))))

(deftest lift2-parallel-test
  (let [[localf seen?] (seen-fn "lift-parallel test x")
        [localfy seeny?] (seen-fn "lift-parallel test y")
        compute (compute/compute-service
                 "node-list"
                 :node-list [(node-list/make-localhost-node
                              :group-name "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :group-name "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (make-node "x1" {} :configure (phase/phase-fn localf))
        y1 (make-node "y1" {} :configure (phase/phase-fn localfy))]
    (is (map?
         (lift [x1 y1]
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute compute
               :environment
               {:algorithms {:lift-fn #'pallet.core/parallel-lift}})))
    (is (seen?))
    (is (seeny?))))

(deftest lift*-nodes-binding-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c" :running false)]
    (mock/expects [(sequential-apply-phase
                    [session servers]
                    (do
                      (is (= #{na nb} (set (:all-nodes session))))
                      (is (= #{na nb} (set (map :node servers))))
                      (is (= #{na nb}
                             (set (map
                                   :node
                                   (-> session :groups first :servers)))))
                      []))]
                  (lift*
                   {:node-set {a #{na nb nc}}
                    :phase-list [:configure]
                    :environment
                    {:compute nil
                     :user utils/*admin-user*
                     :middleware *middleware*
                     :algorithms {:lift-fn sequential-lift}}}))
    (mock/expects [(sequential-apply-phase
                    [session nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes session))))
                      (is (= na
                             (-> session
                                 :groups first :servers first :node)))
                      (is (= nb
                             (-> session
                                 :groups second :servers first :node)))
                      []))]
                  (lift*
                   {:node-set {a #{na} b #{nb}}
                    :phase-list [:configure]
                    :environment
                    {:compute nil
                     :user utils/*admin-user*
                     :middleware *middleware*
                     :algorithms {:lift-fn sequential-lift}}}))))

(deftest lift-multiple-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")
        compute (compute/compute-service "node-list" :node-list [na nb nc])]
    (mock/expects [(compute/nodes [_] [na nb nc])
                   (sequential-apply-phase
                    [session nodes]
                    (do
                      (is (= #{na nb nc} (set (:all-nodes session))))
                      (let [m (into
                               {}
                               (map
                                (juxt :group-name identity) (:groups session)))]
                        (is (= na (-> m :a :servers first :node)))
                        (is (= nb (-> m :b :servers first :node)))
                        (is (= 2 (count (:groups session)))))
                      (is (= 1 (-> session :parameters :x)))
                      []))]
                  (lift [a b] :compute compute :parameters {:x 1}))))

(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propogated
  ;; between phases
  (let [assoc-runtime-param (action/clj-action
                             [session]
                             (parameter/assoc-for-target session [:x] "x"))

        get-runtime-param (action/bash-action
                           [session]
                           (format
                            "echo %s" (parameter/get-for-target session [:x])))
        node (group-spec
                "localhost"
                :phases
                {:configure assoc-runtime-param
                 :configure2 (fn [session]
                               (is (= (parameter/get-for-target session [:x])
                                      "x"))
                               (get-runtime-param session))})
        localhost (node-list/make-localhost-node :group-name "localhost")]
    (testing "serial"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            session (lift {node localhost}
                          :phase [:configure :configure2]
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :compute compute
                          :environment
                          {:algorithms {:lift-fn sequential-lift}})]
        (is (map? session))
        (is (map? (-> session :results)))
        (is (map? (-> session :results first second)))
        (is (-> session :results :localhost :configure))
        (is (-> session :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> session
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))
    (testing "parallel"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            session (lift {node localhost}
                          :phase [:configure :configure2]
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :compute compute
                          :environment
                          {:algorithms {:lift-fn parallel-lift}})]
        (is (map? session))
        (is (map? (-> session :results)))
        (is (map? (-> session :results first second)))
        (is (-> session :results :localhost :configure))
        (is (-> session :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> session
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))))

(action/def-clj-action dummy-local-resource
  [session arg] session)

(deftest lift-with-delayed-argument-test
  ;; test that delayed arguments correcly see parameter updates
  ;; within the same phase
  (let [add-slave (fn [session]
                    (let [target-node (session/target-node session)
                          hostname (compute/hostname target-node)
                          target-ip (compute/primary-ip target-node)]
                      (parameter/update-for-service
                       session
                       [:slaves]
                       (fn [v]
                         (conj (or v #{}) (str hostname "-" target-ip))))))
        seen (atom false)
        get-slaves (fn [session]
                     (reset! seen true)
                     (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                            (parameter/get-for-service session [:slaves]))))
        master (make-node "master" {}
                          :configure (fn [session]
                                       (dummy-local-resource
                                        session
                                        (argument/delayed
                                         [session]
                                         (get-slaves session)))))
        slave (make-node "slave" {} :configure add-slave)
        slaves [(test-utils/make-localhost-node :name "a" :id "a" :group-name "slave")
                (test-utils/make-localhost-node :name "b" :id "b" :group-name "slave")]
        master-node (test-utils/make-localhost-node :name "c" :group-name "master")
        compute (compute/compute-service
                 "node-list" :node-list (conj slaves master-node))]
    (testing "serial"
      (let [session (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves]))))
      (testing "node sequence neutrality"
        (reset! seen false)
        (let [session (lift
                     [slave master]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service session [:slaves]))))))
    (testing "parallel"
      (reset! seen false)
      (let [session (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn parallel-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
               (parameter/get-for-service session [:slaves])))))))

(action/def-clj-action checking-set
  [session]
  (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
         (parameter/get-for-service session [:slaves])))
  session)


(deftest lift-post-phase-test
  (testing
      "test that parameter updates are correctly seen in the post phase"
    (let [add-slave (fn [session]
                      (let [target-node (session/target-node session)
                            hostname (compute/hostname target-node)
                            target-ip (compute/primary-ip target-node)]
                        (parameter/update-for-service
                         session
                         [:slaves]
                         (fn [v]
                           (conj (or v #{}) (str hostname "-" target-ip))))))
          slave (make-node "slave" {} :configure add-slave)
          slaves [(test-utils/make-localhost-node
                   :name "a" :id "a" :group-name "slave")
                  (test-utils/make-localhost-node
                   :name "b" :id "b" :group-name "slave")]
          master-node (test-utils/make-localhost-node
                       :name "c" :id "c" :group-name "master")
          compute (compute/compute-service
                   "node-list" :node-list (conj slaves master-node))]
      (testing "with serial lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (phase/phase-fn
                                            (phase/schedule-in-pre-phase
                                             checking-set
                                             localf-pre)
                                            (phase/schedule-in-post-phase
                                             checking-set
                                             localf-post)))

              session (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service session [:slaves])))))
      (testing "with serial lift in reverse node type order"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (phase/phase-fn
                                            (phase/schedule-in-pre-phase
                                             checking-set
                                             localf-pre)
                                            (phase/schedule-in-post-phase
                                             checking-set
                                             localf-post)))

              session (lift
                       [slave master]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service session [:slaves])))))
      (testing "with parallel lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (phase/phase-fn
                                            (phase/schedule-in-pre-phase
                                             checking-set
                                             localf-pre)
                                            (phase/schedule-in-post-phase
                                             checking-set
                                             localf-post)))

              session (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn parallel-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service session [:slaves]))))))))
