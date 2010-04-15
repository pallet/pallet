(ns pallet.core-test
  (:use [pallet.core] :reload-all)
  (require [pallet.utils])
  (:use
   clojure.test
   pallet.test-utils
   [pallet.compute :only [make-node]]
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

(deftest node-type-test
  (defnode a [])
  (let [anode (make-node "a")]
    (is (= a (node-type anode)))))

(deftest node-type-for-tag-test
  (defnode a [])
  (is (= a (node-type-for-tag :a))))

(deftest node-count-difference-test
  (is (= { {:tag :a} 1 {:tag :b} -1}
         (node-count-difference
          { {:tag :a} 2 {:tag :b} 0}
          [(make-node "a") (make-node "b")])))
  (is (= { {:tag :a} 1 {:tag :b} 1}
         (node-count-difference { {:tag :a} 1 {:tag :b} 1} []))))

(deftest nodes-in-set-test
  (let [a (make-node "a")
        b (make-node "b")]
    (is (= [a] (nodes-in-set a nil)))
    (is (= [a b] (nodes-in-set [a b] nil)))))

(deftest node-in-types?-test
  (defnode a [])
  (defnode b [])
  (is (node-in-types? [a b] (make-node "a")))
  (is (not (node-in-types? [a b] (make-node "c")))))

(deftest nodes-for-types-test
  (defnode a [])
  (defnode b [])
  (let [na (make-node "a")
        nb (make-node "b")
        nc (make-node "c")]
    (is (= [na nb] (nodes-for-types [na nb nc] [a b])))
    (is (= [na] (nodes-for-types [na nc] [a b])))))

(deftest nodes-in-map-test
  (defnode a [])
  (defnode b [])
  (defnode c [])
  (let [na (make-node "a")
        nb (make-node "b")]
    (is (= [na nb] (nodes-in-map {a 1 b 1 c 1} [na nb])))
    (is (= [na] (nodes-in-map {a 1 c 1} [na nb])))))


(defn- test-component-fn [arg]
  (str arg))

(resource/defcomponent test-component test-component-fn [arg & options])

(with-private-vars [pallet.core [node-types]]
  (deftest defnode-test
    (reset! node-types {})
    (defnode fred [:ubuntu])
    (is (= {:tag :fred :image [:ubuntu] :phases {}} fred))
    (is (= {:fred fred} @node-types))
    (defnode tom [:centos])
    (is (= {:tag :tom :image [:centos] :phases {}} tom))
    (is (= {:tom tom :fred fred} @node-types))
    (defnode harry (tom :image))
    (is (= {:tag :harry :image [:centos] :phases {}} harry))
    (is (= {:harry harry :tom tom :fred fred} @node-types))
    (defnode with-phases (tom :image)
      :bootstrap [(test-component :a)]
      :configure [(test-component :b)])
    (is (= [:bootstrap :configure] (keys (with-phases :phases))))
    (is (= ":a\n"
           (resource/produce-phases
            [:bootstrap] "tag" [] (with-phases :phases))))
    (is (= ":b\n"
           (resource/produce-phases
            [:configure] "tag" [] (with-phases :phases))))))
