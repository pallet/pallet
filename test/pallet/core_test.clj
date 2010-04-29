(ns pallet.core-test
  (:use [pallet.core] :reload-all)
  (require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.resource.exec-script :as exec-script]
   [pallet.compute :as compute])
  (:use
   clojure.test
   pallet.test-utils
   [pallet.resource :as resource]))

(deftest with-admin-user-test
  (let [x (rand)]
    (with-admin-user x
      (is (= x pallet.utils/*admin-user*)))))

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
  (is (= {:tag :pa} (add-prefix-to-node-type "p" {:tag :a}))))

(deftest add-prefix-to-node-map-test
  (is (= {{:tag :pa} 1} (add-prefix-to-node-map "p" {{:tag :a} 1}))))

(deftest node-count-difference-test
  (is (= { {:tag :a} 1 {:tag :b} -1}
         (node-count-difference
          { {:tag :a} 2 {:tag :b} 0}
          [(compute/make-node "a") (compute/make-node "b")])))
  (is (= { {:tag :a} 1 {:tag :b} 1}
         (node-count-difference { {:tag :a} 1 {:tag :b} 1} []))))

(deftest nodes-in-set-test
  (defnode a [:ubuntu])
  (defnode b [:ubuntu])
  (defnode pa [:ubuntu])
  (defnode pb [:ubuntu])
  (let [a-node (compute/make-node "a")
        b-node (compute/make-node "b")]
    (is (= {a #{a-node}}
           (nodes-in-set {a a-node} nil nil nil)))
    (is (= {a #{a-node b-node}}
           (nodes-in-set {a #{a-node b-node}} nil nil nil)))
    (is (= {a #{a-node} b #{b-node}}
           (nodes-in-set {a #{a-node} b #{b-node}} nil nil nil))))
  (let [a-node (compute/make-node "a")
        b-node (compute/make-node "b")]
    (is (= {pa #{a-node}}
           (nodes-in-set {a a-node} "p" nil nil)))
    (is (= {pa #{a-node b-node}}
           (nodes-in-set {a #{a-node b-node}} "p" nil nil)))
    (is (= {pa #{a-node} pb #{b-node}}
           (nodes-in-set {a #{a-node} b #{b-node}} "p" nil nil)))))

(deftest node-in-types?-test
  (defnode a [])
  (defnode b [])
  (is (node-in-types? [a b] (compute/make-node "a")))
  (is (not (node-in-types? [a b] (compute/make-node "c")))))

(deftest nodes-for-type-test
  (defnode a [])
  (defnode b [])
  (let [na (compute/make-node "a")
        nb (compute/make-node "b")
        nc (compute/make-node "c")]
    (is (= [nb] (nodes-for-type [na nb nc] b)))
    (is (= [na] (nodes-for-type [na nc] a)))))

(deftest nodes-in-map-test
  (defnode a [])
  (defnode b [])
  (defnode c [])
  (let [na (compute/make-node "a")
        nb (compute/make-node "b")]
    (is (= [na nb] (nodes-in-map {a 1 b 1 c 1} [na nb])))
    (is (= [na] (nodes-in-map {a 1 c 1} [na nb])))))

(deftest compute-service-and-options-test
  (binding [org.jclouds.compute/*compute* :compute]
    (is (= [:compute nil 'a '()]
           (compute-service-and-options 'a [])))
        (is (= [:compute "prefix" 'a '()]
           (compute-service-and-options "prefix" ['a])))))

(defn- test-component-fn [arg]
  (str arg))

(resource/defresource test-component test-component-fn [arg & options])


(deftest defnode-test
  (defnode fred [:ubuntu])
  (is (= {:tag :fred :image [:ubuntu] :phases {}} fred))
  (defnode tom [:centos])
  (is (= {:tag :tom :image [:centos] :phases {}} tom))
  (defnode harry (tom :image))
  (is (= {:tag :harry :image [:centos] :phases {}} harry))
  (defnode with-phases (tom :image)
    :bootstrap [(test-component :a)]
    :configure [(test-component :b)])
  (is (= [:bootstrap :configure] (keys (with-phases :phases))))
  (is (= ":a\n"
         (resource/produce-phases
          [:bootstrap] "tag" [] (with-phases :phases))))
  (is (= ":b\n"
         (resource/produce-phases
          [:configure] "tag" [] (with-phases :phases)))))

(deftest lift-test
  (defnode x [])
  (is (.contains "/bin"
       (with-no-compute-service
         (with-admin-user (assoc utils/*admin-user* :no-sudo true)
           (with-out-str
             (lift {x (compute/make-unmanaged-node "x" "localhost")}
                   (phase
                    (exec-script/exec-script
                     (stevedore/script
                      (ls "/")))))))))))
