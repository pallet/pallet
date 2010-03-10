(ns pallet.core-test
  (:use [pallet.core] :reload-all)
  (require [pallet.utils])
  (:use clojure.test
        pallet.test-utils))

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

(deftest with-node-templates-test
  (let [x (rand)]
    (with-node-templates x
      (is (= x *node-templates*)))))

(deftest configure-nodes-none-test
  (let [x (rand)]
    (is (= x (configure-nodes-none "unused" x)))))

(with-private-vars [pallet.core [bootstrap-script-fn]]
  (deftest bootstrap-script-fn-test
    (is (nil? ((bootstrap-script-fn nil) "template" [])))
    (is (= "template1" ((bootstrap-script-fn str) "template" "1")))
    (is (= "1template2\ntemplate2"
           ((bootstrap-script-fn [(partial str "1") str]) "template" "2")))
    (is (= "2template3\ntemplate3"
           ((bootstrap-script-fn (sequence [(partial str "2") str])) "template" "3")))))
