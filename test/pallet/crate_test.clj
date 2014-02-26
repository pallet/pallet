(ns pallet.crate-test
  (:require
   [clojure.test :refer :all]))

;; (deftest groups-with-role-test
;;   (let [session (test-session
;;                  {:service-state
;;                   {:group->nodes {(group-spec "group1" :roles :role1) []
;;                                   (group-spec "group2" :roles :role2) []}}})]
;;     (is (= [[:group1] session]
;;              ((groups-with-role :role1) session)))))

;; (deftest nodes-with-role-test
;;   (let [n1 (make-node "group1")
;;         n2 (make-node "group2")
;;         g1 (group-spec "group1" :roles :role1)
;;         g2 (group-spec "group2" :roles :role2)
;;         targets [(assoc g1 :node n1) (assoc g2 :node n2)]
;;         session (test-session {:service-state targets})]
;;     (is (= [(first targets)]
;;            (with-session session
;;              (nodes-with-role :role1))))))

;; (defmulti-plan xx
;;   (fn [k]
;;     (:xx (session))))

;; (defmethod-plan xx :yy
;;   [k]
;;   [k])

;; (deftest defmulti-plan-test
;;   (let [s (add-session-verification-key {:xx :yy})]
;;     (with-session s
;;       (is (= [:k] (xx :k))))))
