(ns pallet.core.jclouds-core-test
  (:use pallet.core)
  (require
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource.exec-script :as exec-script]
   [pallet.compute :as compute]
   [pallet.compute.jclouds :as jclouds]
   [pallet.compute.node-list :as node-list]
   [pallet.target :as target]
   [pallet.mock :as mock]
   [pallet.compute.jclouds-test-utils :as jclouds-test-utils]
   [pallet.compute.jclouds-ssh-test :as ssh-test]
   [pallet.action :as action]
   [pallet.build-actions :as build-actions]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.test-utils :as test-utils])
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


(deftest add-prefix-to-node-type-test
  (is (= {:tag :pa} (#'core/add-prefix-to-node-type "p" {:tag :a}))))

(deftest add-prefix-to-node-map-test
  (is (= {{:tag :pa} 1} (#'core/add-prefix-to-node-map "p" {{:tag :a} 1}))))

(deftest node-count-difference-test
  (is (= { {:tag :a} 1 {:tag :b} -1}
         (#'core/node-count-difference
          { {:tag :a} 2 {:tag :b} 0}
          [(jclouds/make-node "a") (jclouds/make-node "b")])))
  (is (= { {:tag :a} 1 {:tag :b} 1}
         (#'core/node-count-difference { {:tag :a} 1 {:tag :b} 1} []))))

(deftest add-os-family-test
  (defnode a {:os-family :ubuntu})
  (defnode b {})
  (let [n1 (jclouds/make-node "n1" :operating-system {:version nil})]
    (is (= {:tag :a :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family {:target-node n1 :node-type a})))))
  (let [n1 (jclouds/make-node
            "n1"
            :operating-system (OperatingSystem.
                               OsFamily/UBUNTU
                               "Ubuntu"
                               nil
                               "Some arch"
                               "Desc"
                               true))]
    (is (= {:tag :a :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family {:target-node n1 :node-type a}))))
    (is (= {:tag :b :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family {:target-node n1 :node-type b}))))))

(deftest add-target-packager-test
  (is (= {:node-type {:image {:os-family :ubuntu}} :target-packager :aptitude}
         (#'core/add-target-packager
          {:node-type {:image {:os-family :ubuntu}}}))))

;; NEED to make this work multi-threaded
(deftest converge-node-counts-test
  (defnode a {:os-family :ubuntu})
  (let [a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (#'core/converge-node-counts
     {a 1} [a-node]
     {:environment
      {:compute org.jclouds.compute/*compute*
       :algorithms {:converge-fn #'pallet.core/serial-adjust-node-counts
                    :lift-fn #'pallet.core/sequential-lift}}}))
  (mock/expects [(org.jclouds.compute/run-nodes
                  [tag n template compute]
                  (mock/once
                   (is (= n 1))))
                 (org.jclouds.compute/build-template
                  [compute & options]
                  (mock/once :template))]
                (let [a-node (jclouds/make-node
                              "a" :state NodeState/TERMINATED)]
                  (#'core/converge-node-counts
                   {a 1}
                   [a-node]
                   {:environment
                    {:compute org.jclouds.compute/*compute*
                     :algorithms
                     {:converge-fn #'pallet.core/serial-adjust-node-counts
                      :lift-fn #'pallet.core/sequential-lift}}}))))

(deftest parallel-converge-node-counts-test
  (defnode a {:os-family :ubuntu})
  (let [a-node (jclouds/make-node "a" :state NodeState/RUNNING)]
    (#'core/converge-node-counts
     {a 1} [a-node]
     {:environment
      {:compute org.jclouds.compute/*compute*
       :algorithms {:converge-fn #'pallet.core/parallel-adjust-node-counts
                    :lift-fn #'pallet.core/parallel-lift}}}))
  (mock/expects [(clojure.core/future-call
                  [f]
                  (mock/once (delay (f)))) ;; delay implements deref
                 (org.jclouds.compute/run-nodes
                  [tag n template compute]
                  (mock/once
                   (is (= n 1))))
                 (org.jclouds.compute/build-template
                  [compute & options]
                  (mock/once :template))]
                (let [a-node (jclouds/make-node
                              "a" :state NodeState/TERMINATED)]
                  (#'core/converge-node-counts
                   {a 1}
                   [a-node]
                   {:environment
                    {:compute org.jclouds.compute/*compute*
                     :algorithms
                     {:converge-fn #'pallet.core/parallel-adjust-node-counts
                      :lift-fn #'pallet.core/parallel-lift}}}))))

(deftest nodes-in-map-test
  (defnode a {:os-family :ubuntu})
  (defnode b {:os-family :ubuntu})
  (let [a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")
        nodes [a-node b-node]]
    (is (= [a-node]
           (#'core/nodes-in-map {a 1} nodes)))
    (is (= [a-node b-node]
           (#'core/nodes-in-map {a 1 b 2} nodes)))))

(deftest nodes-in-set-test
  (defnode a {:os-family :ubuntu})
  (defnode b {:os-family :ubuntu})
  (defnode pa {:os-family :ubuntu})
  (defnode pb {:os-family :ubuntu})
  (let [a-node (jclouds/make-node "a")
        b-node (jclouds/make-node "b")]
    (is (= {a #{a-node}}
           (#'core/nodes-in-set {a a-node} nil nil)))
    (is (= {a #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
    (is (= {a #{a-node} b #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
  (let [a-node (jclouds/make-node "a")
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

(deftest nodes-for-type-test
  (defnode a {})
  (defnode b {})
  (let [na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c")]
    (is (= [nb] (#'core/nodes-for-type [na nb nc] b)))
    (is (= [na] (#'core/nodes-for-type [na nc] a)))))

(deftest nodes-in-map-test
  (defnode a {})
  (defnode b {})
  (defnode c {})
  (let [na (jclouds/make-node "a")
        nb (jclouds/make-node "b")]
    (is (= [na nb] (#'core/nodes-in-map {a 1 b 1 c 1} [na nb])))
    (is (= [na] (#'core/nodes-in-map {a 1 c 1} [na nb])))))

(def test-component
  (action/bash-action [request arg] (str arg)))

(deftest make-node-test
  (is (= {:tag :fred :image {:os-family :ubuntu} :phases nil}
         (make-node "fred" {:os-family :ubuntu})))
  (is (= {:tag :tom :image {:os-family :centos} :phases nil}
         (make-node "tom" {:os-family :centos}))))

(deftest defnode-test
  (defnode fred {:os-family :ubuntu})
  (is (= {:tag :fred :image {:os-family :ubuntu} :phases nil} fred))
  (defnode tom "This is tom" {:os-family :centos})
  (is (= {:tag :tom :image {:os-family :centos} :phases nil} tom))
  (is (= "This is tom" (:doc (meta #'tom))))
  (defnode harry (tom :image))
  (is (= {:tag :harry :image {:os-family :centos} :phases nil} harry))
  (defnode with-phases (tom :image)
    :bootstrap (phase/phase-fn (test-component :a))
    :configure (phase/phase-fn (test-component :b)))
  (is (= #{:bootstrap :configure} (set (keys (with-phases :phases)))))
  (let [request {:target-node (jclouds/make-node "tag" :id "id")
                 :target-id :id
                 :node-type with-phases
                 :target-packager :yum}]
    (is (= ":a\n"
           (first
            (build-actions/build-actions
             [:phase :bootstrap :node-type with-phases]))))
    (is (= ":b\n"
           (first
            (build-actions/build-actions
             [:phase :configure :node-type with-phases]))))))

(def identity-resource (action/bash-action [request x] x))
(def identity-local-resource (action/clj-action [request] request))

(deftest bootstrap-script-test
  (is (= "a\n"
         (#'core/bootstrap-script
          {:node-type {:image {:os-family :ubuntu}
                       :phases {:bootstrap (phase/phase-fn
                                            (identity-resource "a"))}}
           :target-packager :ubuntu
           :target-id :id})))
  (testing "rejects local resources"
    (is (thrown?
         clojure.contrib.condition.Condition
         (#'core/bootstrap-script
          {:node-type
           {:image {:os-family :ubuntu}
            :phases {:bootstrap (phase/phase-fn (identity-local-resource))}}
           :target-packager :ubuntu
           :target-id :id})))))

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
  (defnode local {})
  (testing "jclouds"
    (let [[localf seen?] (seen-fn "lift-test")]
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
        x1 (make-node "x1" {} :configure (phase/phase-fn localf))
        y1 (make-node "y1" {} :configure (phase/phase-fn localfy))]
    (binding [org.jclouds.compute/*compute* nil]
      (is (map?
           (lift {x1 (jclouds/make-unmanaged-node "x" "localhost")
                  y1 (jclouds/make-unmanaged-node "y" "localhost")}
                 :user (assoc utils/*admin-user*
                         :username (test-utils/test-username)
                         :no-sudo true)))))
    (is (seen?))
    (is (seeny?))))

(deftest lift*-nodes-binding-test
  (defnode a {})
  (defnode b {})
  (let [na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= #{na nb} (set (:target-nodes request))))
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
                    [request nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= #{na nb} (set (:target-nodes request))))
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
  (defnode a {})
  (defnode b {})
  (let [na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "c")]
    (mock/expects [(org.jclouds.compute/nodes-with-details [_] [na nb nc])
                   (sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb nc} (set (:all-nodes request))))
                      (is (= #{na nb} (set (:target-nodes request))))
                      []))]
                  (lift [a b] :compute org.jclouds.compute/*compute*))))

(deftest converge*-nodes-binding-test
  (defnode a {})
  (defnode b {})
  (let [na (jclouds/make-node "a")
        nb (jclouds/make-node "b")
        nc (jclouds/make-node "b" :state NodeState/TERMINATED)]
    (mock/expects [(sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= #{na nb} (set (:target-nodes request))))
                      []))
                   (org.jclouds.compute/nodes-with-details [& _] [na nb nc])]
                  (converge*
                   {:node-map {a 1 b 1}
                    :phase-list [:configure]
                    :environment
                    {:compute org.jclouds.compute/*compute*
                     :middleware *middleware*
                     :algorithms
                     {:converge-fn #'pallet.core/serial-adjust-node-counts
                      :lift-fn sequential-lift}}}))))

(deftest converge-test
  (let [id "a"
        node (make-node "a" {}
                        :configure (fn [request]
                                     (action/schedule-action
                                      request
                                      (fn [request] "Hi")
                                      [] :in-sequence :script/bash :target)))
        request (converge {node 2}
                          :compute org.jclouds.compute/*compute*
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
    (is (= 2 (count (org.jclouds.compute/nodes))))
    (testing "remove some instances"
      (let [reqeust (converge {node 1}
                              :compute org.jclouds.compute/*compute*
                              :middleware [core/translate-action-plan
                                           execute/execute-echo])]
        (Thread/sleep 300) ;; stub destroyNode is asynchronous ?
        (is (= 1 (count (compute/nodes org.jclouds.compute/*compute*))))))
    (testing "remove all instances"
      (let [request (converge {node 0}
                              :compute org.jclouds.compute/*compute*
                              :middleware [core/translate-action-plan
                                           execute/execute-echo])]
        (is (= 0 (count (filter
                         (complement compute/terminated?)
                         (:all-nodes request)))))))))

  ;; (org.jclouds.compute/with-compute-service
  ;;   [(pallet.compute/compute-service
  ;;     "stub" "" "" :extensions [(ssh-test/ssh-test-client
  ;;                                ssh-test/no-op-ssh-client)])]
  ;;   (jclouds-test-utils/purge-compute-service)
  ;;   (let [id "a"
  ;;         node (make-node "a" {}
  ;;                         :configure (fn [request]
  ;;                                      (action/schedule-action
  ;;                                       request
  ;;                                       (fn [request] "Hi")
  ;;                                       [] :in-sequence :script/bash :target)))
  ;;         request (with-middleware
  ;;                   wrap-no-exec
  ;;                   (converge {node 2} :compute org.jclouds.compute/*compute*))]
  ;;     (is (map? request))
  ;;     (is (map? (-> request :results)))
  ;;     (is (map? (-> request :results first second)))
  ;;     (is (:configure (-> request :results first second)))
  ;;     (is (some
  ;;          #(= "Hi\n" %)
  ;;          (:configure (-> request :results first second))))
  ;;     (is (= 2 (count (:all-nodes request))))
  ;;     (is (= 2 (count (org.jclouds.compute/nodes))))
  ;;     (testing "remove some instances"
  ;;       (let [reqeust (with-middleware
  ;;                       wrap-no-exec
  ;;                       (converge {node 1}
  ;;                                 :compute org.jclouds.compute/*compute*))]
  ;;         (Thread/sleep 300) ;; stub destroyNode is asynchronous ?
  ;;         (is (= 1 (count (compute/nodes org.jclouds.compute/*compute*))))))
  ;;     (testing "remove all instances"
  ;;       (let [request (with-middleware
  ;;                       wrap-no-exec
  ;;                       (converge {node 0}
  ;;                                 :compute org.jclouds.compute/*compute*))]
  ;;         (is (= 0 (count (filter
  ;;                          (complement compute/terminated?)
  ;;                          (:all-nodes request)))))))))

(action/def-clj-action parameter-resource
  [request]
  (parameter/assoc-for-target request [:x] "x"))

(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propogated
  ;; between phases
  (let [node (make-node
              "localhost" {}
              :configure (fn [request]
                           (action/schedule-action
                            request
                            (fn [request]
                              (parameter/assoc-for-target request [:x] "x"))
                            [] :in-sequence :fn/clojure :origin))
              :configure2 (fn [request]
                            (is (= (parameter/get-for-target request [:x])
                                   "x"))
                            (action/schedule-action
                             request
                             (fn [request]
                               (format
                                "echo %s\n"
                                (parameter/get-for-target request [:x])))
                             [] :in-sequence :script/bash :target)))
        request (lift {node (jclouds/make-localhost-node)}
                      :phase [:configure :configure2]
                      :user (assoc utils/*admin-user* :no-sudo true)
                      :compute org.jclouds.compute/*compute*)]
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
