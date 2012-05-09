(ns pallet.utils-test
  (:use pallet.utils)
  (:use clojure.test
        clojure.tools.logging
        pallet.test-utils))

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
