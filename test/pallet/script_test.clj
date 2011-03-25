(ns pallet.script-test
  (:use pallet.script)
  (:require [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.script [matches? more-explicit?]]
  (deftest matches?-test
    (with-script-context [:ubuntu]
      (is (matches? [:ubuntu]))
      (is (not (matches? [:fedora])))
      (is (not (matches? [:ubuntu :smallest]))))
    (with-script-context [:ubuntu :smallest]
      (is (matches? [:ubuntu]))
      (is (matches? [:smallest]))
      (is (not (matches? [:fedora])))
      (is (matches? [:ubuntu :smallest]))))

  (deftest more-explicit?-test
    (is (more-explicit? :default [:anything]))
    (is (more-explicit? [:something] [:anything :longer]))
    (is (not (more-explicit? [:something :longer] [:anything])))))

(deftest script-fn-test
  (testing "no varargs"
    (let [f (script-fn [a b])]
      (is (= :anonymous (:fn-name f)))
      (with-script-context [:a]
        (is (thrown?
             clojure.contrib.condition.Condition
             (dispatch f [1 1])))
        (implement f :default (fn [a b] b))
        (is (= 2 (dispatch f [1 2]))))))
  (testing "varargs"
    (let [f (script-fn [a b & c])]
      (with-script-context [:a]
        (is (thrown?
             clojure.contrib.condition.Condition
             (dispatch f [1 1 2 3])))
        (implement f :default (fn [a b & c] c))
        (is (= [2 3] (dispatch f [1 1 2 3]))))))
  (testing "named"
    (let [f (script-fn :fn1 [a b])]
      (is (= :fn1 (:fn-name f))))))

(deftest best-match-test
  (let [s (script-fn [])
        f1 (fn [] 1)
        f2 (fn [] 2)]
    (implement s :default f1)
    (implement s [:os-x] f2)
    (with-script-context [:centos :yum]
      (is (= f1 (#'pallet.script/best-match @(:methods s))))
      (is (= 1 (invoke s []))))
    (with-script-context [:os-x :brew]
      (is (= f2 (#'pallet.script/best-match @(:methods s))))
      (is (= 2 (invoke s []))))))

(deftest defscript-test
  (with-script-context [:a]
    (testing "no varargs"
      (defscript script1a [a b])
      (is (nil? (:doc (meta script1a))))
      (is (= '([a b]) (:arglists (meta #'script1a))))
      (implement script1a :default (fn [a b] b))
      (is (= 2 (dispatch script1a [1 2]))))
    (testing "varargs"
      (defscript script2 "doc" [a b & c])
      (is (= "doc" (:doc (meta #'script2))))
      (is (= '([a b & c]) (:arglists (meta #'script2))))
      (implement script2 :default (fn [a b & c] c))
      (is (= [2 3] (dispatch script2 [1 1 2 3]))))))

(deftest dispatch-test
  (let [x (script-fn [a])]
    (testing "with no implementation"
      (testing "should raise"
        (pallet.stevedore/with-script-fn-dispatch
          script-fn-dispatch
          (with-script-context [:ubuntu]
            (is (thrown? clojure.contrib.condition.Condition
                         (pallet.stevedore/script (~x 2))))))))
    (testing "with an implementation"
      (defimpl x :default [a] (str "x" ~a 1))
      (testing "and mandatory dispatch"
        (pallet.stevedore/with-script-fn-dispatch
          script-fn-dispatch
          (with-script-context [:ubuntu]
            (is (= "x21" (pallet.stevedore/script (~x 2))))))))))
