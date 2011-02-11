(ns pallet.script-test
  (:use pallet.script)
  (:require [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.script [matches? more-explicit?]]
  (deftest matches?-test
    (with-template [:ubuntu]
      (is (matches? [:ubuntu]))
      (is (not (matches? [:fedora])))
      (is (not (matches? [:ubuntu :smallest]))))
    (with-template [:ubuntu :smallest]
      (is (matches? [:ubuntu]))
      (is (matches? [:smallest]))
      (is (not (matches? [:fedora])))
      (is (matches? [:ubuntu :smallest]))))

  (deftest more-explicit?-test
    (is (more-explicit? :default [:anything]))
    (is (more-explicit? [:something] [:anything :longer]))
    (is (not (more-explicit? [:something :longer] [:anything])))))

(deftest best-match-test
  (defscript best-match-script)
  (let [f1 (fn [] 1)
        f2 (fn [] 2)]
    (implement :best-match-script :default f1)
    (implement :best-match-script [:os-x] f2)
    (with-template [:centos :yum]
      (is (= f1 (#'pallet.script/best-match :best-match-script)))
      (is (= 1 (invoke-target :best-match-script []))))
    (with-template [:os-x :brew]
      (is (= f2 (#'pallet.script/best-match :best-match-script)))
      (is (= 2 (invoke-target :best-match-script []))))))

(deftest defscript-test
  (defscript script1 [a b])
  (is (nil? (:doc (meta script1))))
  (defscript script2 "doc" [a b])
  (is (= "doc" (:doc (meta script2)))))
