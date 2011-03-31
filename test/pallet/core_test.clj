(ns pallet.core-test
  (:use pallet.core)
  (require
   [pallet.argument :as argument]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core :as core]
   [pallet.mock :as mock]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.resource-build :as resource-build]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.string :as string])
  (:use
   clojure.test))

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


(deftest add-prefix-to-node-type-test
  (is (= {:tag :pa} (#'core/add-prefix-to-node-type "p" {:tag :a}))))

(deftest add-prefix-to-node-map-test
  (is (= {{:tag :pa} 1} (#'core/add-prefix-to-node-map "p" {{:tag :a} 1}))))

(deftest node-count-difference-test
  (is (= { {:tag :a} 1 {:tag :b} -1}
         (#'core/node-count-difference
          { {:tag :a} 2 {:tag :b} 0}
          [(test-utils/make-node "a") (test-utils/make-node "b")])))
  (is (= { {:tag :a} 1 {:tag :b} 1}
         (#'core/node-count-difference { {:tag :a} 1 {:tag :b} 1} []))))

(deftest add-os-family-test
  (defnode a {:os-family :ubuntu})
  (defnode b {})
  (let [n1 (test-utils/make-node "n1" )]
    (is (= {:tag :a :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family
             {:target-node n1 :node-type a})))))
  (let [n1 (test-utils/make-node "n1")]
    (is (= {:tag :a :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family
             {:target-node n1 :node-type a}))))
    (is (= {:tag :b :image {:os-family :ubuntu :os-version nil} :phases nil}
           (:node-type
            (#'core/add-os-family
             {:target-node n1 :node-type b})))))
  (let [n1 (test-utils/make-node "n1" :os-version "10.1")]
    (is (= {:tag :a :image {:os-family :ubuntu :os-version "10.1"} :phases nil}
           (:node-type
            (#'core/add-os-family
             {:target-node n1 :node-type a}))))))

(deftest add-target-packager-test
  (is (= {:node-type {:image {:os-family :ubuntu}} :target-packager :aptitude}
         (#'core/add-target-packager
          {:node-type {:image {:os-family :ubuntu}}}))))

(deftest converge-node-counts-test
  (defnode a {:os-family :ubuntu})
  (let [a-node (test-utils/make-node "a" :running true)
        compute (compute/compute-service "node-list" :node-list [a-node])]
    (#'core/converge-node-counts
     {a 1} [a-node]
     {:environment
      {:compute compute
       :algorithms {:lift-fn sequential-lift
                    :converge-fn
                    (var-get #'core/serial-adjust-node-counts)}}})))

(deftest nodes-in-map-test
  (defnode a {:os-family :ubuntu})
  (defnode b {:os-family :ubuntu})
  (let [a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")
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
  (let [a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")]
    (is (= {a #{a-node}}
           (#'core/nodes-in-set {a a-node} nil nil)))
    (is (= {a #{a-node b-node}}
           (#'core/nodes-in-set {a #{a-node b-node}} nil nil)))
    (is (= {a #{a-node} b #{b-node}}
           (#'core/nodes-in-set {a #{a-node} b #{b-node}} nil nil))))
  (let [a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")]
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
  (is (#'core/node-in-types? [a b] (test-utils/make-node "a")))
  (is (not (#'core/node-in-types? [a b] (test-utils/make-node "c")))))

(deftest nodes-for-type-test
  (defnode a {})
  (defnode b {})
  (let [na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")]
    (is (= [nb] (#'core/nodes-for-type [na nb nc] b)))
    (is (= [na] (#'core/nodes-for-type [na nc] a)))))

(deftest nodes-in-map-test
  (defnode a {})
  (defnode b {})
  (defnode c {})
  (let [na (test-utils/make-node "a")
        nb (test-utils/make-node "b")]
    (is (= [na nb] (#'core/nodes-in-map {a 1 b 1 c 1} [na nb])))
    (is (= [na] (#'core/nodes-in-map {a 1 c 1} [na nb])))))

(deftest build-request-map-test
  (binding [pallet.core/*middleware* :middleware]
    (testing "defaults"
      (is (= {:environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware :middleware
               :algorithms {:lift-fn sequential-lift
                            :converge-fn
                            (var-get #'core/serial-adjust-node-counts)}}}
             (#'core/build-request-map {}))))
    (testing "passing a prefix"
      (is (= {:environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware *middleware*
               :algorithms {:lift-fn sequential-lift
                            :converge-fn
                            (var-get #'core/serial-adjust-node-counts)}}
              :prefix "prefix"}
             (#'core/build-request-map {:prefix "prefix"}))))
    (testing "passing a user"
      (let [user (utils/make-user "fred")]
        (is (= {:environment
                {:blobstore nil :compute nil  :user user
                 :middleware :middleware
                 :algorithms {:lift-fn sequential-lift
                              :converge-fn
                              (var-get #'core/serial-adjust-node-counts)}}}
               (#'core/build-request-map {:user user})))))))

(resource/defresource test-component
  (test-component-fn
   [request arg & options]
   (str arg)))

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
    :bootstrap (resource/phase (test-component :a))
    :configure (resource/phase (test-component :b)))
  (is (= #{:bootstrap :configure} (set (keys (with-phases :phases)))))
  (let [request {:target-node (test-utils/make-node "tag" :id "id")
                 :target-id :id
                 :node-type with-phases
                 :target-packager :yum}]
    (is (= ":a\n"
           (first
            (resource-build/produce-phases
             [:bootstrap]
             (#'core/resource-invocations (assoc request :phase :bootstrap))))))
    (is (= ":b\n"
           (first
            (resource-build/produce-phases
             [:configure]
             (#'core/resource-invocations
              (assoc request :phase :configure))))))))

(resource/defresource identity-resource
  (identity-resource* [request x] x))

(resource/deflocal identity-local-resource
  (identity-local-resource* [request] request))

(deftest produce-init-script-test
  (is (= "a\n"
         (#'core/produce-init-script
          {:node-type {:image {:os-family :ubuntu}
                       :phases {:bootstrap (resource/phase
                                            (identity-resource "a"))}}
           :target-id :id
           :target-packager :aptitude})))
  (testing "rejects local resources"
    (is (thrown?
         clojure.contrib.condition.Condition
         (#'core/produce-init-script
          {:node-type
           {:image {:os-family :ubuntu}
            :phases {:bootstrap (resource/phase (identity-local-resource))}}
           :target-id :id
           :target-packager :aptitude}))))
  (testing "requires a packager"
    (is (thrown?
         java.lang.AssertionError
         (#'core/produce-init-script
          {:node-type {:image {:os-family :ubuntu}}}))))
  (testing "requires an os-family"
    (is (thrown?
         java.lang.AssertionError
         (#'core/produce-init-script
          {:node-type {:image {}}
           :target-packager :yum})))))



(defmacro seen-fn
  "Generate a local function, which uses an atom to record when it is called."
  [name]
  (let [localf-sym (gensym "localf")
        localf*-sym (gensym "localf*")
        seen-sym (gensym "seen")]
    `(let [~seen-sym (atom nil)
           seen?# (fn [] @~seen-sym)]
       (resource/deflocal ~localf-sym
         (~localf*-sym
          [request#]
          (clojure.contrib.logging/info (format "Seenfn %s" ~name))
          (testing (format "not already seen %s" ~name)
            (is (not @~seen-sym)))
          (reset! ~seen-sym true)
          (is (:target-node request#))
          (is (:node-type request#))
          request#))
       [~localf-sym seen?#])))

(deftest warn-on-undefined-phase-test
  (binding [clojure.contrib.logging/impl-write! (fn [_ _ msg _] (println msg))]
    (is (= "Undefined phases: a, b\n"
           (with-out-str (#'core/warn-on-undefined-phase {} [:a :b])))))
  (binding [clojure.contrib.logging/impl-write! (fn [_ _ msg _] (println msg))]
    (is (= "Undefined phases: b\n"
           (with-out-str
             (#'core/warn-on-undefined-phase
              {(make-node "fred" {} :a (fn [_] _)) 1}
              [:a :b]))))))

(deftest lift-test
  (defnode local {})
  (testing "node-list"
    (let [[localf seen?] (seen-fn "lift-test")
          service (compute/compute-service
                   "node-list"
                   :node-list [(node-list/make-localhost-node :tag "local")])]
      (is (re-find
           #"bin"
           (->
             (lift local
                   :phase [(resource/phase (exec-script/exec-script (ls "/")))
                           (resource/phase (localf))]
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
                   :phase [(resource/phase (exec-script/exec-script (ls "/")))
                           (resource/phase (localf))]
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
                              :tag "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :tag "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (make-node "x1" {} :configure (resource/phase localf))
        y1 (make-node "y1" {} :configure (resource/phase localfy))]
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
                              :tag "x1" :name "x1" :id "x1"
                              :os-family :ubuntu)
                             (node-list/make-localhost-node
                              :tag "y1" :name "y1" :id "y1"
                              :os-family :ubuntu)])
        x1 (make-node "x1" {} :configure (resource/phase localf))
        y1 (make-node "y1" {} :configure (resource/phase localfy))]
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
  (defnode a {})
  (defnode b {})
  (let [na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c" :running false)]
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
                     :algorithms {:lift-fn sequential-lift}}}))
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
                     :algorithms {:lift-fn sequential-lift}}}))))

(deftest lift-multiple-test
  (defnode a {})
  (defnode b {})
  (let [na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")
        compute (compute/compute-service "node-list" :node-list [na nb nc])]
    (mock/expects [(compute/nodes [_] [na nb nc])
                   (sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb nc} (set (:all-nodes request))))
                      (is (= #{na nb} (set (:target-nodes request))))
                      (is (= 1 (-> request :parameters :x)))
                      []))]
                  (lift [a b] :compute compute :parameters {:x 1}))))

(deftest lift-with-runtime-params-test
  ;; test that parameters set at execution time are propogated
  ;; between phases
  (let [node (make-node
              "localhost" {}
              :configure (fn [request]
                           (resource/invoke-resource
                            request
                            (fn [request]
                              (parameter/assoc-for-target request [:x] "x"))
                            [] :in-sequence :fn/clojure))
              :configure2 (fn [request]
                            (is (= (parameter/get-for-target request [:x])
                                   "x"))
                            (resource/invoke-resource
                             request
                             (fn [request]
                               (format
                                "echo %s\n"
                                (parameter/get-for-target request [:x])))
                             [] :in-sequence :script/bash)))
        localhost (node-list/make-localhost-node :tag "localhost")]
    (testing "serial"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            request (lift {node localhost}
                          :phase [:configure :configure2]
                          :compute compute
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :environment
                          {:algorithms {:lift-fn sequential-lift}})]
        (is (map? request))
        (is (map? (-> request :results)))
        (is (map? (-> request :results first second)))
        (is (-> request :results :localhost :configure))
        (is (-> request :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> request
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))
    (testing "parallel"
      (let [compute (compute/compute-service "node-list" :node-list [localhost])
            request (lift {node localhost}
                          :phase [:configure :configure2]
                          :compute compute
                          :user (assoc utils/*admin-user*
                                  :username (test-utils/test-username)
                                  :no-sudo true)
                          :environment
                          {:algorithms {:lift-fn parallel-lift}})]
        (is (map? request))
        (is (map? (-> request :results)))
        (is (map? (-> request :results first second)))
        (is (-> request :results :localhost :configure))
        (is (-> request :results :localhost :configure2))
        (let [{:keys [out err exit]} (-> request
                                         :results :localhost :configure2 first)]
          (is out)
          (is (string/blank? err))
          (is (zero? exit)))))))


(resource/deflocal dummy-local-resource
  (dummy-local-resource* [request arg] request))

(deftest lift-with-delayed-argument-test
  ;; test that delayed arguments correcly see parameter updates
  ;; within the same phase
  (let [add-slave (fn [request]
                    (let [target-node (:target-node request)
                          hostname (compute/hostname target-node)
                          target-ip (compute/primary-ip target-node)]
                      (parameter/update-for-service
                       request
                       [:slaves]
                       (fn [v]
                         (conj (or v #{}) (str hostname "-" target-ip))))))
        seen (atom false)
        get-slaves (fn [request]
                     (reset! seen true)
                     (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                            (parameter/get-for-service request [:slaves]))))

        master (make-node "master" {}
                          :configure (fn [request]
                                       (dummy-local-resource
                                        request
                                        (argument/delayed
                                         [request]
                                         (get-slaves request)))))
        slave (make-node "slave" {} :configure add-slave)
        slaves [(test-utils/make-localhost-node :name "a" :id "a" :tag "slave")
                (test-utils/make-localhost-node :name "b" :id "b" :tag "slave")]
        master-node (test-utils/make-localhost-node :name "c" :tag "master")
        compute (compute/compute-service
                 "node-list" :node-list (conj slaves master-node))]
    (testing "serial"
      (let [request (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service request [:slaves]))))
      (testing "node sequence neutrality"
        (let [request (lift
                     [slave master]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn sequential-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service request [:slaves]))))))
    (testing "parallel"
      (let [request (lift
                     [master slave]
                     :compute compute
                     :user (assoc utils/*admin-user*
                             :username (test-utils/test-username)
                             :no-sudo true)
                     :environment {:algorithms {:lift-fn parallel-lift}})]
        (is @seen "get-slaves should be called")
        (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                 (parameter/get-for-service request [:slaves])))))))

(resource/deflocal checking-set
  (checking-set*
   [request]
   (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
            (parameter/get-for-service request [:slaves])))
   request))

(deftest lift-post-phase-test
  (testing
      "test that parameter updates are correctly seen in the post phase"
    (let [add-slave (fn [request]
                      (let [target-node (:target-node request)
                            hostname (compute/hostname target-node)
                            target-ip (compute/primary-ip target-node)]
                        (parameter/update-for-service
                         request
                         [:slaves]
                         (fn [v]
                           (conj (or v #{}) (str hostname "-" target-ip))))))
          slave (make-node "slave" {} :configure add-slave)
          slaves [(test-utils/make-localhost-node
                   :name "a" :id "a" :tag "slave")
                  (test-utils/make-localhost-node
                   :name "b" :id "b" :tag "slave")]
          master-node (test-utils/make-localhost-node
                       :name "c" :id "c" :tag "master")
          compute (compute/compute-service
                   "node-list" :node-list (conj slaves master-node))]
      (testing "with serial lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (resource/phase
                                            (resource/execute-pre-phase
                                             checking-set
                                             localf-pre)
                                            (resource/execute-after-phase
                                             checking-set
                                             localf-post)))

              request (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service request [:slaves])))))
      (testing "with serial lift in reverse node type order"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (resource/phase
                                            (resource/execute-pre-phase
                                             checking-set
                                             localf-pre)
                                            (resource/execute-after-phase
                                             checking-set
                                             localf-post)))

              request (lift
                       [slave master]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn sequential-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service request [:slaves])))))
      (testing "with parallel lift"
        (let [[localf-pre seen-pre?] (seen-fn "lift-post-phase-test pre")
              [localf-post seen-post?] (seen-fn "lift-post-phase-test post")
              master (make-node "master" {}
                                :configure (resource/phase
                                            (resource/execute-pre-phase
                                             checking-set
                                             localf-pre)
                                            (resource/execute-after-phase
                                             checking-set
                                             localf-post)))

              request (lift
                       [master slave]
                       :compute compute
                       :user (assoc utils/*admin-user*
                               :username (test-utils/test-username)
                               :no-sudo true)
                       :environment {:algorithms {:lift-fn parallel-lift}})]
          (is (seen-pre?) "checking-not-set should be called")
          (is (seen-post?) "checking-set should be called")
          (is (= #{"a-127.0.0.1" "b-127.0.0.1"}
                   (parameter/get-for-service request [:slaves]))))))))
