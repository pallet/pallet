(ns pallet.utils-test
  (:require
   [clojure.test :refer :all]
   [clojure.tools.logging :refer :all]
   [pallet.test-utils :refer :all]
   [pallet.utils :refer :all]))

(defn- unload-ns
  "Hack to unload a namespace - remove-ns does not remove it from *loaded-libs*"
  [sym]
  (remove-ns sym)
  (sync []
   (alter (var-get #'clojure.core/*loaded-libs*) disj sym)))

(deftest find-var-with-require-test
  (testing "symbol with namespace"
    (unload-ns 'pallet.utils-test-ns)
    (Thread/sleep 1000)
    (is (= 1 (find-var-with-require 'pallet.utils-test-ns/sym-1)))
    (is (= 1 @(find-var-with-require 'pallet.utils-test-ns/sym-atom)))
    (testing "no reload of existing ns"
      (swap! (find-var-with-require 'pallet.utils-test-ns/sym-atom) inc)
      (is (= 2 @(find-var-with-require 'pallet.utils-test-ns/sym-atom)))))
  (testing "symbol and namespace"
    (unload-ns 'pallet.utils-test-ns)
    (Thread/sleep 1000)
    (is (= 1 (find-var-with-require 'pallet.utils-test-ns 'sym-1)))
    (is (= 1 @(find-var-with-require 'pallet.utils-test-ns 'sym-atom)))
    (testing "no reload of existing ns"
      (swap! (find-var-with-require 'pallet.utils-test-ns 'sym-atom) inc)
      (is (= 2 @(find-var-with-require 'pallet.utils-test-ns 'sym-atom)))))
  (testing "non-existing namespace"
    (is (nil? (find-var-with-require 'pallet.utils-test.non-existant/sym))))
  (testing "non-compiling namespace"
    (remove-ns 'pallet.utils-test-bad-ns)
    (is (thrown? Exception
                 (find-var-with-require 'pallet.utils-test-bad-ns 'sym)))
    ;; test twice to exercice issue with require still creating a namespace on
    ;; failure
    (is (thrown? Exception
                 (find-var-with-require 'pallet.utils-test-bad-ns 'sym)))))


(deftest middleware-test
  (let [f1 (fn [c] (fn [x] (c (inc x))))
        f2 (fn [c] (fn [x] (c (* 2 x))))
        mw (middleware f1 f2)]
    (is (= 4 ((mw identity) 1)))))

(deftest total-order-merge-test
  (is (= [:a :b :c :d] (total-order-merge [:a :b] [:c :d])))
  (is (= [:a :b :c] (total-order-merge [:a :b] [:b :c])))
  (is (= [:a :b] (total-order-merge [:a :b] [:a :b])))
  (is (= [:a :b :c] (total-order-merge [:b :c] [:a :b])))
  (is (= [:a :b :c :d] (total-order-merge [:a :d] [:b :c :d])))
  (is (= [:a :b :c :d :e :f] (total-order-merge [:a :b] [:c :d] [:e :f])))
  (is (= [:a :b :c :d :e :f]
         (total-order-merge
          [:a :b :e] [:b :c :e] [:a :b :d :f] [:e :f] [:d :e])))
  (is (= [:a :b :c :d :e :f]
         (total-order-merge [:a :c] [:b :c][:b :d] [:c :e] [:d :f])))
  (testing "no total ordering"
    (is (thrown-with-msg? Exception #"No total ordering"
                          (total-order-merge
                           [:a :c] [:c :a])))))

(deftest count-by-test
  (is (= {:a 1 :b 2} (count-by :k [{:k :a} {:k :b} {:k :b}]))))

(deftest count-values-test
  (is (= {:a 1 :b 2} (count-values [:b :a :b]))))

(deftest map-seq-test
  (is (nil? (map-seq {})))
  (is (= {:a 1} (map-seq {:a 1}))))
