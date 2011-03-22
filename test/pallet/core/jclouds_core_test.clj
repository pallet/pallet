(ns pallet.core.jclouds-core-test
  (:use pallet.core)
  (require
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.compute :as compute]
   [pallet.compute.jclouds :as jclouds]
   [pallet.target :as target]
   [pallet.mock :as mock]
   [pallet.compute.jclouds-test-utils :as jclouds-test-utils]
   [pallet.compute.jclouds-ssh-test :as ssh-test]
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.resource :as resource]
   [pallet.resource-build :as resource-build]
   [pallet.test-utils :as test-utils]
   [clojure.contrib.logging :as logging])
  (:use
   clojure.test)
  (:import [org.jclouds.compute.domain NodeState OperatingSystem OsFamily]))

;; Allow running against other compute services if required
(def *compute-service* ["stub" "" "" ])

(use-fixtures
  :each
  (jclouds-test-utils/compute-service-fixture
   *compute-service*
   :extensions
   [(ssh-test/ssh-test-client ssh-test/no-op-ssh-client)]))

(use-fixtures :once (test-utils/console-logging-threshold))

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

(deftest converge-node-counts-test
  (let [a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (is
     (= [a-node]
          (->
           (#'core/converge-node-counts
            {:groups [(test-utils/group :a :count 1 :servers [{:node a-node}])]
             :environment
             {:compute (jclouds-test-utils/compute)
              :algorithms {:converge-fn #'pallet.core/serial-adjust-node-counts
                           :lift-fn #'pallet.core/sequential-lift}}})
           :all-nodes))))
  (let [build-template org.jclouds.compute/build-template
        a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (mock/expects [(org.jclouds.compute/run-nodes
                    [tag n template compute]
                    (mock/once
                     (is (= "a" tag))
                     (is (= 1 n))
                     [a-node]))
                   (org.jclouds.compute/build-template
                    [compute & options]
                    (mock/times 2 (apply build-template compute options)))]
                  (is
                   (= [a-node]
                        (->
                         (#'core/converge-node-counts
                          {:groups [(test-utils/group :a :count 1 :servers [])]
                           :environment
                           {:compute (jclouds-test-utils/compute)
                            :algorithms
                            {:converge-fn
                             #'pallet.core/serial-adjust-node-counts
                             :lift-fn #'pallet.core/sequential-lift}}})
                         :all-nodes))))))

(deftest parallel-converge-node-counts-test
  (let [a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (is
     (= [a-node]
          (->
           (#'core/converge-node-counts
            {:groups [(test-utils/group :a :count 1 :servers [{:node a-node}])]
             :environment
             {:compute (jclouds-test-utils/compute)
              :algorithms {:converge-fn
                           #'pallet.core/parallel-adjust-node-counts
                           :lift-fn #'pallet.core/parallel-lift}}})
           :all-nodes))))
  (let [build-template org.jclouds.compute/build-template
        a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (mock/expects [(clojure.core/future-call
                    [f]
                    (mock/once (delay (f)))) ;; delay implements deref
                   (org.jclouds.compute/run-nodes
                    [tag n template compute]
                    (mock/once
                     (is (= 1 n))
                     [a-node]))
                   (org.jclouds.compute/build-template
                    [compute & options]
                    (mock/times 2 (apply build-template compute options)))]
                  (is
                   (=
                    [a-node]
                    (->
                     (#'core/converge-node-counts
                      {:groups [(test-utils/group :a :count 1)]
                       :environment
                       {:compute (jclouds-test-utils/compute)
                        :algorithms
                        {:converge-fn
                         #'pallet.core/parallel-adjust-node-counts
                         :lift-fn #'pallet.core/parallel-lift}}})
                     :all-nodes))))))

(deftest nodes-in-set-test
  (let [a (group-spec "a" :image {:os-family :ubuntu})
        b (group-spec "b" :image {:os-family :ubuntu})
        a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")]
    (is (= {a #{a-node}}
           (#'core/nodes-in-set {a a-node} nil nil)))
    (is (= {a #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
    (is (= {a #{a-node} b #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
  (let [a (group-spec "a" :image {:os-family :ubuntu})
        b (group-spec "b" :image {:os-family :ubuntu})
        pa (group-spec "pa" :image {:os-family :ubuntu})
        pb (group-spec "pb" :image {:os-family :ubuntu})
        a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")]
    (is (= {pa #{a-node}}
           (#'core/nodes-in-set {a a-node} "p" nil)))
    (is (= {pa #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} "p" nil)))
    (is (= {pa #{a-node} pb #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} "p" nil)))
    (is (= {pa #{a-node} pb #{b-node}}
           (#'core/nodes-in-set {a a-node b b-node} "p" nil)))))

(deftest node-in-types?-test
  (defnode a {})
  (defnode b {})
  (is (#'core/node-in-types? [a b] (jclouds/make-node "a")))
  (is (not (#'core/node-in-types? [a b] (jclouds/make-node "c")))))

(def test-component
  (action/bash-action [request arg] (str arg)))

(def identity-resource (action/bash-action [request x] x))
(def identity-local-resource (action/clj-action [request] request))

(defn seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [seen (atom nil)
        seen? (fn [] @seen)]
    [(action/clj-action
       [request]
       (clojure.contrib.logging/info (format "Seenfn %s" name))
       (is (not @seen))
       (reset! seen true)
       (is (:target-node request))
       (is (:node-type request))
       request)
      seen?]))

(deftest lift-test
  (testing "jclouds"
    (let [local (group-spec "local")
          [localf seen?] (seen-fn "lift-test")]
      (is (.contains
           "bin"
           (with-out-str
             (lift {local (jclouds/make-localhost-node)}
                   :phase [(phase/phase-fn (exec-script/exec-script (ls "/")))
                           (phase/phase-fn (localf))]
                   :user (assoc utils/*admin-user*
                           :username (test-utils/test-username)
                           :no-sudo true)
                   :compute nil))))
      (is (seen?)))))

(deftest lift2-test
  (let [[localf seen?] (seen-fn "lift2-test")
        [localfy seeny?] (seen-fn "lift2-test y")
        x1 (group-spec "x1" :phases {:configure (resource/phase localf)})
        y1 (group-spec "y1" :phases {:configure (resource/phase localfy)})]
    (is (map?
         (lift {x1 (jclouds/make-unmanaged-node "x" "localhost")
                y1 (jclouds/make-unmanaged-node "y" "localhost")}
               :user (assoc utils/*admin-user*
                       :username (test-utils/test-username)
                       :no-sudo true)
               :compute nil)))
    (is (seen?))
    (is (seeny?))))

(deftest lift*-nodes-binding-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [request group-nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= #{na nb} (set (map :node group-nodes))))
                      (is (= #{na nb}
                             (set (map
                                   :node
                                   (-> request :groups first :servers)))))
                      []))]
                  (lift*
                   {:node-set {a #{na nb nc}}
                    :phase-list [:configure]
                    :environment
                    {:compute nil
                     :user utils/*admin-user*
                     :middleware *middleware*
                     :algorithms
                     {:converge-fn #'pallet.core/serial-adjust-node-counts
                      :lift-fn sequential-lift}}}))
    (mock/expects [(sequential-apply-phase
                    [request group-nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= na
                             (-> request
                                 :groups first :servers first :node)))
                      (is (= nb
                             (-> request
                                 :groups second :servers first :node)))
                      []))]
                  (lift*
                   {:node-set {a #{na} b #{nb}}
                    :phase-list [:configure]
                    :environment
                    {:compute nil
                     :user utils/*admin-user*
                     :middleware *middleware*
                     :algorithms
                     {:converge-fn #'pallet.core/serial-adjust-node-counts
                      :lift-fn sequential-lift}}}))))

(deftest lift-multiple-test
  (let [a (group-spec "a")
        b (group-spec "b")
        na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c")]
    (mock/expects [(org.jclouds.compute/nodes-with-details
                     [_]
                     (mock/once [na nb nc]))
                   (sequential-apply-phase
                    [request group-nodes]
                    (mock/times 6 ;; 2 groups :pre, :after, :configure
                      (is (= #{na nb nc} (set (:all-nodes request))))
                      (let [m (into
                               {}
                               (map (juxt :group-name identity)
                                    (:groups request)))]
                        (is (= na (-> m :a :servers first :node)))
                        (is (= nb (-> m :b :servers first :node)))
                        (is (= 2 (count (:groups request)))))
                      []))]
                  (lift [a b] :compute (jclouds-test-utils/compute)
                        :environment
                        {:algorithms
                         {:lift-fn pallet.core/sequential-lift}}))))

(deftest create-nodes-test
  (let [a (jclouds/make-node "a")
        nodes (#'core/create-nodes
               (group-spec :a :servers [{:node a}]) 1
               {:compute (jclouds-test-utils/compute)})]
    (is (seq nodes))
    (is (= 2 (count nodes)))
    (is (= "a" (compute/tag (first nodes))))
    (is (= "a" (compute/tag (second nodes))))))

(deftest destroy-nodes-test
  (testing "remove all"
    (let [a (jclouds/make-node "a")
          nodes (#'core/destroy-nodes
                 (test-utils/group :a :servers [{:node a}]) 1
                 {:compute (jclouds-test-utils/compute)})]
      (is (nil? (seq nodes)))))
  (testing "remove some"
    (let [a (jclouds/make-node "a")
          b (jclouds/make-node "a")
          nodes (#'core/destroy-nodes
                 (test-utils/group :a :servers [{:node a} {:node b}]) 1
                 {:compute (jclouds-test-utils/compute)})]
      (is (seq nodes))
      (is (= 1 (count nodes)))
      (is (= "a" (compute/tag (first nodes)))))))

(deftest converge*-test
  (logging/info "converge*-test")
  (let [a (make-node :a {})
        b (make-node :b {})
        na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nb2 (jclouds/make-node "b" :id "b2" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      []))
                   (org.jclouds.compute/nodes-with-details [_] [na nb nb2])]
                  (converge*
                   {:node-set [(assoc a :count 1) (assoc b :count 1)]
                    :phase-list [:configure]
                    :environment
                    {:compute (jclouds-test-utils/compute)
                     :middleware *middleware*
                     :algorithms
                     {:converge-fn #'pallet.core/serial-adjust-node-counts
                      :lift-fn sequential-lift}}})))
  (logging/info "converge*-test end"))

(deftest converge-test
  (let [hi (action/bash-action [request] "Hi")
        id "a"
        node (make-node "a" {} :configure hi)
        request (converge {node 2}
                          :compute (jclouds-test-utils/compute)
                          :middleware [core/translate-action-plan
                                       execute/execute-echo])]
    (is (map? request))
    (is (map? (-> request :results)))
    (is (map? (-> request :results first second)))
    (is (:configure (-> request :results first second)))
    (is (some
         #(= "Hi\n" %)
         (:configure (-> request :results first second))))
    (is (= 2 (count (:all-nodes request))))
    (is (= 2 (count (org.jclouds.compute/nodes (jclouds-test-utils/compute)))))
    (testing "remove some instances"
      (let [reqeust (converge {node 1}
                              :compute (jclouds-test-utils/compute)
                              :middleware [core/translate-action-plan
                                           execute/execute-echo])]
        (Thread/sleep 300) ;; stub destroyNode is asynchronous ?
        (is (= 1 (count (compute/nodes (jclouds-test-utils/compute)))))))
    (testing "remove all instances"
      (let [request (converge {node 0}
                              :compute (jclouds-test-utils/compute)
                              :middleware [core/translate-action-plan
                                           execute/execute-echo])]
        (is (= 0 (count (filter
                         (complement compute/terminated?)
                         (:all-nodes request)))))))))


(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propogated
  ;; between phases
  (let [assoc-runtime-param (action/clj-action
                             [request]
                             (parameter/assoc-for-target request [:x] "x"))

        get-runtime-param (action/bash-action
                           [request]
                           (format
                            "echo %s" (parameter/get-for-target request [:x])))
        node (make-node
              "localhost" {}
              :configure assoc-runtime-param
              :configure2 (fn [request]
                            (is (= (parameter/get-for-target request [:x])
                                   "x"))
                            (get-runtime-param request)))
        request (lift {node (jclouds/make-localhost-node)}
                      :phase [:configure :configure2]
                      :user (assoc utils/*admin-user* :no-sudo true)
                      :compute (jclouds-test-utils/compute))]
    (is (map? request))
    (is (map? (-> request :results)))
    (is (map? (-> request :results first second)))
    (is (-> request :results :localhost :configure))
    (is (-> request :results :localhost :configure2))
    (let [{:keys [out err exit]} (-> request
                                     :results :localhost :configure2 first)]
      (is out)
      (is (= err ""))
      (is (zero? exit)))))
