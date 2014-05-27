(ns pallet.version-dispatch-test
  (:require
   [clojure.test :refer :all]
   [pallet.kb :refer [os-hierarchy]]
   [pallet.plan :refer [defmethod-plan defmethod-every]]
   [pallet.test-utils]
   [pallet.build-actions :refer [target-session]]
   [pallet.version-dispatch :refer :all]))

(defmulti-version os-ver [os os-ver ver arg] os-hierarchy)

(defmethod-plan
    os-ver {:os :rhel :os-version [1 0] :version nil}
    [id os-version version arg]
  [arg 1])
(defmethod-plan
    os-ver {:os :rh-base :os-version [[2 0] nil] :version [2 1]}
    [os os-version version arg]
  [arg 2])
(defmethod-plan
    os-ver {:os :centos :os-version [nil [1 0]] :version [nil [3 1]]}
    [os os-version version arg]
  [arg 3])
(defmethod-plan
    os-ver {:os :debian :os-version [[1 0] [2 0]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 4])
(defmethod-plan
    os-ver {:os :ubuntu :os-version [[1 0] [2 0]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 5])
(defmethod-plan
    os-ver {:os :ubuntu :os-version [[1 2] [1 3]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 6])
(defmethod-plan
    os-ver {:os :ubuntu :os-version [[1 1] [1 4]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 7])

(deftest basic
  (testing "basic dispatch"
    (is (= [::arg 1]  (os-ver :rhel [1 0] [9 9] ::arg)))
    (is (= [::arg 2]  (os-ver :rhel [3 0] [2 1] ::arg)))
    (is (= [::arg 3]  (os-ver :centos [0 9 1] [3 1] ::arg)))
    (is (= [::arg 4]  (os-ver :debian [1 9 1] [4 3] ::arg))))
  (testing "overlapped dispatch"
    (is (= [::arg 6]  (os-ver :ubuntu [1 2 5] [4 2] ::arg)))))

(deftest os-map-test
  (testing "default-value"
    (let [m (os-map {})]
      (is (nil? (:f m)))
      (is (= ::x (:f m ::x)))
      (testing "ifn lookup"
        (is (= ::x (m :f ::x))))))
  (testing "default"
    (let [m (os-map {:default 1})]
      (is (= 1 (:f m)))
      (testing "ifn lookup"
        (is (= 1 (m :f)))
        (is (= ::x (m :f ::x))))))
  (testing "exact"
    (let [m (os-map
             {{:os :ubuntu :os-version [12 04]} 1})
          key {:os :ubuntu :os-version [12 04]}]
      (is (= 1 (get m key)))
      (testing "dissoc"
        (is (= ::nil (get (dissoc m key) key ::nil))))
      (testing "assoc"
        (let [key2 {:os :debian :os-version [6]}]
          (is (= 1 (get (assoc m key2 1) key2 ::nil))))))))

(defmulti-version xx [os os-version version])
(defmethod-every xx {:os :ubuntu :os-version [13] :version [1]}
  [os os-version version]
  :a)

(defmethod-every xx {:os :centos :os-version [5] :version [1]}
  [os os-version version]
  :b)


(deftest defmulti-version-test
  (is (thrown-cause-with-msg?
         Exception
         #"Invalid dispatch vector.*"
         (eval `(defmulti-version ~(gensym "xxx") [])))
      "error for defmulti-version with insufficient dispatch args.")
  (let [xxx (gensym "xxx")]
    (is (thrown-cause-with-msg?
         Exception
         (re-pattern (str "Could not find defmulti " (name xxx)))
         (eval `(defmethod-version ~xxx {} [])))
        "error for defmethod-version on nonexistent defmulti-version."))
  (is (= :a (xx :ubuntu [13 04] [1 1])))
  (is (= :b (xx :centos [5 1] [1 2])))
  (is (thrown-cause-with-msg?
       Exception #"Dispatch failed in xx" (xx :arch [1] [1]))
      "Error for no matching dispatch."))

(defmulti-version-plan yy [session version])
(defmethod-plan yy {:os :ubuntu :os-version [13] :version [1]}
  [session version]
  :a)

(defmethod-plan yy {:os :centos :os-version [5] :version [1]}
  [session version]
  :b)

(deftest defmulti-version-plan-test
  (is (thrown-cause-with-msg?
       Exception
       #"Invalid dispatch vector.*"
       (eval `(defmulti-version-plan ~(gensym "xxx") [])))
      "error for defmulti-version-plan with insufficient dispatch args.")
  (let [xxx (gensym "xxx")]
    (is
     (thrown-cause-with-msg?
      Exception
      (re-pattern (str "Could not find defmulti-plan " (name xxx)))
      (eval `(defmethod-version-plan ~xxx {} [])))
     "error for defmethod-version-plan on nonexistent defmulti-version-plan."))
  (let [s (target-session {:target {:os-family :ubuntu :os-version "13.04"}})]
    (is (= :a (yy s [1 1]))))
  (let [s (target-session {:target {:os-family :centos :os-version "5.1"}})]
    (is (= :b (yy s [1 2]))))
  (is (thrown-cause-with-msg?
       Exception #"Dispatch failed in yy"
       (let [s (target-session {:target {:os-family :arch :os-version "1"}})]
         (yy s [1])))
      "Error for no matching dispatch."))
