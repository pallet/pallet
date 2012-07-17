(ns pallet.version-dispatch-test
  (:use
   clojure.test
   pallet.version-dispatch
   [pallet.compute :only [os-hierarchy]]))

(defmulti-version os-ver [os os-ver ver arg] #'os-hierarchy)

(defmethod-version
    os-ver {:os :rhel :os-version [1 0] :version nil}
    [id os-version version arg]
  [arg 1])
(defmethod-version
    os-ver {:os :rh-base :os-version [[2 0] nil] :version [2 1]}
    [os os-version version arg]
  [arg 2])
(defmethod-version
    os-ver {:os :centos :os-version [nil [1 0]] :version [nil [3 1]]}
    [os os-version version arg]
  [arg 3])
(defmethod-version
    os-ver {:os :debian :os-version [[1 0] [2 0]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 4])
(defmethod-version
    os-ver {:os :ubuntu :os-version [[1 0] [2 0]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 5])
(defmethod-version
    os-ver {:os :ubuntu :os-version [[1 2] [1 3]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 6])
(defmethod-version
    os-ver {:os :ubuntu :os-version [[1 1] [1 4]] :version [[4 1] [4 3]]}
    [os os-version version arg]
  [arg 7])

(deftest basic
  (is (:hierarchy (meta #'os-ver)))
  (is (:methods (meta #'os-ver)))
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
        (is (= 1 (m :f ::x))))))
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
