(ns pallet.core-test
  (:use [pallet.core] :reload-all)
  (:use clojure.test
        pallet.test-utils))

(deftest with-admin-user-test
  (let [x (rand)]
    (with-admin-user x
      (is (= x *admin-user*)))))

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
