(ns pallet.core-test
  (:use pallet.core)
  (require
   [pallet.core :as core]
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource.exec-script :as exec-script]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.target :as target]
   [pallet.mock :as mock]
   [pallet.resource :as resource]
   [pallet.resource-build :as resource-build]
   [pallet.test-utils :as test-utils])
  (:use
   clojure.test))

(use-fixtures :once (test-utils/console-logging-threshold))

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


(deftest node-spec-with-prefix-test
  (is (= {:tag :pa} (#'core/node-spec-with-prefix "p" {:tag :a}))))

(deftest node-map-with-prefix-test
  (is (= {{:tag :pa} 1} (#'core/node-map-with-prefix "p" {{:tag :a} 1}))))

(deftest node-count-difference-test
  (is (= {:a 1 :b -1}
         (#'core/node-count-difference
          [{:tag :a :count 2 :group-nodes [(test-utils/make-node "a")]}
           {:tag :b :count 0 :group-nodes [(test-utils/make-node "b")]}])))
  (is (= {:a 1 :b 1}
         (#'core/node-count-difference
          [{:tag :a :count 1} {:tag :b :count 1}]))))

(deftest converge-node-counts-test
  (let [a (node-spec "a" :image {:os-family :ubuntu})
        a-node (test-utils/make-node "a" :running true)
        compute (compute/compute-service "node-list" :node-list [a-node])]
    (#'core/converge-node-counts
     {:groups [{:tag :a :count 1 :group-nodes [{:node a-node}]}]
      :environment
      {:compute compute
       :algorithms {:lift-fn sequential-lift
                    :converge-fn
                    (var-get #'core/serial-adjust-node-counts)}}})))

(deftest nodes-in-map-test
  (let [a (node-spec "a" :image {:os-family :ubuntu})
        b (node-spec "b" :image {:os-family :ubuntu})
        a-node (test-utils/make-node "a")
        b-node (test-utils/make-node "b")
        nodes [a-node b-node]]
    (is (= [a-node]
             (#'core/nodes-in-map {a 1} nodes)))
    (is (= [a-node b-node]
             (#'core/nodes-in-map {a 1 b 2} nodes))))
  (let [a (node-spec "a")
        b (node-spec "b")
        c (node-spec "c")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")]
    (is (= [na nb] (#'core/nodes-in-map {a 1 b 1 c 1} [na nb])))
    (is (= [na] (#'core/nodes-in-map {a 1 c 1} [na nb])))))

(deftest node-spec?-test
  (is (#'core/node-spec? (core/node-spec "a")))
  (is (#'core/node-spec? (core/make-node "a" {}))))

(deftest nodes-in-set-test
  (let [a (make-node :a {:os-family :ubuntu})
        b (make-node :b {:os-family :ubuntu})
        pa (make-node :pa {:os-family :ubuntu})
        pb (make-node :pb {:os-family :ubuntu})]
    (testing "sequence of groups"
      (let [a-node (test-utils/make-node "a")
            b-node (test-utils/make-node "b")]
        (is (= {a #{a-node} b #{b-node}}
               (#'core/nodes-in-set [a b] nil [a-node b-node])))))
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
             (#'core/nodes-in-set {a a-node b b-node} "p" nil))))))

(deftest node-in-types?-test
  (let [a (node-spec "a")
        b (node-spec "b")]
    (is (#'core/node-in-types? [a b] (test-utils/make-node "a")))
    (is (not (#'core/node-in-types? [a b] (test-utils/make-node "c"))))))

(deftest nodes-for-type-test
  (let [a (node-spec "a")
        b (node-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")]
    (is (= [nb] (#'core/nodes-for-type [na nb nc] b)))
    (is (= [na] (#'core/nodes-for-type [na nc] a)))))

(deftest group-node-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= {:node-id :id
            :tag :a
            :packager :aptitude
            :image {:os-version "v"
                    :os-family :ubuntu}
            :phases nil
            :node n}
           (group-node a n {})))))

(deftest groups-with-nodes-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= [{:group-nodes [{:node-id :id
                            :tag :a
                            :packager :aptitude
                            :image {:os-version "v"
                                    :os-family :ubuntu}
                            :phases nil
                            :node n}]
             :tag :a
             :image {}
             :phases nil}]
             (groups-with-nodes {a #{n}})))
    (testing "with options"
      (is (= [{:group-nodes [{:node-id :id
                              :tag :a
                              :packager :aptitude
                              :image {:os-version "v"
                                      :os-family :ubuntu}
                              :phases nil
                              :node n
                              :extra 1}]
               :tag :a
               :image {}
               :phases nil}]
               (groups-with-nodes {a #{n}} :extra 1))))))

(deftest request-with-groups-test
  (let [a (make-node :a {})
        n (test-utils/make-node
           "a" :os-family :ubuntu :os-version "v" :id "id")]
    (is (= {:groups [{:group-nodes [{:node-id :id
                                     :tag :a
                                     :packager :aptitude
                                     :image {:os-version "v"
                                             :os-family :ubuntu}
                                     :node n
                                     :phases nil}]
                      :tag :a
                      :image {}
                      :phases nil}]
            :all-nodes [n]
            :node-set {a #{n}}}
           (request-with-groups
             {:all-nodes [n] :node-set {a #{n}}})))
    (testing "with-options"
      (is (= {:groups [{:group-nodes [{:node-id :id
                                       :tag :a
                                       :packager :aptitude
                                       :image {:os-version "v"
                                               :os-family :ubuntu}
                                       :phases nil
                                       :node n
                                       :invoke-only true}]
                        :tag :a
                        :image {}
                        :phases nil}]
              :all-nodes [n]
              :node-set nil
              :all-node-set {a #{n}}}
             (request-with-groups
               {:all-nodes [n] :node-set nil :all-node-set {a #{n}}}))))))


(deftest request-with-environment-test
  (binding [pallet.core/*middleware* :middleware]
    (testing "defaults"
      (is (= {:environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware :middleware
               :algorithms {:lift-fn sequential-lift
                            :converge-fn
                            (var-get #'core/serial-adjust-node-counts)}}}
             (#'core/request-with-environment {}))))
    (testing "passing a prefix"
      (is (= {:environment
              {:blobstore nil :compute nil :user utils/*admin-user*
               :middleware *middleware*
               :algorithms {:lift-fn sequential-lift
                            :converge-fn
                            (var-get #'core/serial-adjust-node-counts)}}
              :prefix "prefix"}
             (#'core/request-with-environment {:prefix "prefix"}))))
    (testing "passing a user"
      (let [user (utils/make-user "fred")]
        (is (= {:environment
                {:blobstore nil :compute nil  :user user
                 :middleware :middleware
                 :algorithms {:lift-fn sequential-lift
                              :converge-fn
                              (var-get #'core/serial-adjust-node-counts)}}}
               (#'core/request-with-environment {:user user})))))))

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
  (defnode node-with-phases (tom :image)
    :bootstrap (resource/phase (test-component :a))
    :configure (resource/phase (test-component :b)))
  (is (= #{:bootstrap :configure} (set (keys (node-with-phases :phases)))))
  (let [request {:group-node {:node-id :id
                              :tag :tag
                              :packager :yum
                              :image {}
                              :node (test-utils/make-node "tag" :id "id")
                              :phases (:phases node-with-phases)}}]
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

(deftest bootstrap-script-test
  (is (= "a\n"
         (#'core/bootstrap-script
          {:group {:image {:os-family :ubuntu}
                   :packager :aptitude
                   :phases {:bootstrap (resource/phase
                                        (identity-resource "a"))}}})))
  (testing "rejects local resources"
    (is (thrown-with-msg?
          clojure.contrib.condition.Condition
          #"local resources"
          (#'core/bootstrap-script
           {:group
            {:image {:os-family :ubuntu}
             :packager :aptitude
             :phases {:bootstrap (resource/phase
                                  (identity-local-resource))}}}))))
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
          (is (not @~seen-sym))
          (reset! ~seen-sym true)
          (is (:group-node request#))
          (is (:group request#))
          request#))
       [~localf-sym seen?#])))

(deftest warn-on-undefined-phase-test
  (test-utils/logging-to-stdout
    (is (= "Undefined phases: a, b\n"
           (with-out-str (#'core/warn-on-undefined-phase nil [:a :b])))))
  (test-utils/logging-to-stdout
    (is (= "Undefined phases: b\n"
           (with-out-str
             (#'core/warn-on-undefined-phase
              [{:phases {:a identity}}]
              [:a :b]))))))

(deftest lift-test
  (testing "node-list"
    (let [local (node-spec "local")
          [localf seen?] (seen-fn "1")
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
            (-> :results :localhost)
            pr-str)))
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

(deftest lift*-nodes-binding-test
  (let [a (node-spec "a")
        b (node-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c" :running false)]
    (mock/expects [(sequential-apply-phase
                    [request group-nodes]
                    (do
                      (is (= #{na nb} (set (:all-nodes request))))
                      (is (= #{na nb} (set (map :node group-nodes))))
                      (is (= #{na nb}
                             (set (map
                                   :node
                                   (-> request :groups first :group-nodes)))))
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
                      (is (= na
                             (-> request
                                 :groups first :group-nodes first :node)))
                      (is (= nb
                             (-> request
                                 :groups second :group-nodes first :node)))
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
  (let [a (node-spec "a")
        b (node-spec "b")
        na (test-utils/make-node "a")
        nb (test-utils/make-node "b")
        nc (test-utils/make-node "c")
        compute (compute/compute-service "node-list" :node-list [na nb nc])]
    (mock/expects [(compute/nodes [_] [na nb nc])
                   (sequential-apply-phase
                    [request nodes]
                    (do
                      (is (= #{na nb nc} (set (:all-nodes request))))
                      (let [m (into
                               {}
                               (map (juxt :tag identity) (:groups request)))]
                        (is (= na (-> m :a :group-nodes first :node)))
                        (is (= nb (-> m :b :group-nodes first :node)))
                        (is (= 2 (count (:groups request)))))
                      (is (= 1 (-> request :parameters :x)))
                      []))]
                  (lift [a b] :compute compute :parameters {:x 1}))))

;; (deftest converge*-nodes-binding-test
;;   (defnode a {})
;;   (defnode b {})
;;   (let [na (test-utils/make-node "a")
;;         nb (test-utils/make-node "b")
;;         nc (test-utils/make-node "b" :name "b1" :running false)
;;         compute (compute/compute-service "node-list" :node-list [na nb nc])]
;;     (mock/expects [(sequential-apply-phase
;;                     [request nodes]
;;                     (do
;;                       (is (= #{na nb} (set (:all-nodes request))))
;;                       (is (= #{na nb} (set (:target-nodes request))))))
;;                    (compute/nodes [& _] [na nb nc])]
;;                   (converge*
;;                    {a 1 b 1} nil [:configure]
;;                    {:compute compute
;;                     :middleware *middleware*}))))

;; (deftest converge-test
;;   (let [id "a"
;;         request (with-middleware
;;                   wrap-no-exec
;;                   (converge {(make-node
;;                               "a" {}
;;                               :configure (fn [request]
;;                                            (resource/invoke-resource
;;                                             request
;;                                             (fn [request] "Hi")
;;                                             [] :in-sequence :script/bash)))
;;                              1} :compute nil))]
;;     (is (map? request))
;;     (is (map? (-> request :results)))
;;     (is (map? (-> request :results first second)))
;;     (is (:configure (-> request :results first second)))
;;     (is (some
;;          #(= "Hi\n" %)
;;          (:configure (-> request :results first second))))
;;     (is (= 1 (count (:all-nodes request))))
;;     (is (= 1 (count (compute/nodes))))))
