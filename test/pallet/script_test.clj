(ns pallet.script-test
  (:use pallet.script)
  (:require [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.script [matches? more-explicit?]]
  (deftest matches?-test
    (target/with-target nil {:image [:ubuntu]}
      (is (matches? [:ubuntu]))
      (is (not (matches? [:fedora])))
      (is (not (matches? [:ubuntu :smallest]))))
    (target/with-target nil {:image [:ubuntu :smallest]}
      (is (matches? [:ubuntu]))
      (is (matches? [:smallest]))
      (is (not (matches? [:fedora])))
      (is (matches? [:ubuntu :smallest]))))

  (deftest more-explicit?-test
    (is (more-explicit? :default [:anything]))
    (is (more-explicit? [:something] [:anything :longer]))
    (is (not (more-explicit? [:something :longer] [:anything])))))
