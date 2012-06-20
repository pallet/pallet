(ns pallet.session-test
  (:require
   [pallet.core :as core]
   [pallet.session :as session]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest groups-with-role-test
  (is (= [:group1] (session/groups-with-role
                     {:all-node-set #{(core/group-spec "group1" :roles :role1)
                                      (core/group-spec "group2" :roles :role2)}}
                     :role1))))

(deftest nodes-with-role-test
  (let [n1 (test-utils/make-node "group1")
        n2 (test-utils/make-node "group2")]
    (is (= [n1] (session/nodes-with-role
                  {:all-node-set #{(core/group-spec "group1" :roles :role1)
                                   (core/group-spec "group2" :roles :role2)}
                   :all-nodes [n1 n2]}
                  :role1)))))
