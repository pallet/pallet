(ns pallet.script-test
  (:use [pallet.script] :reload-all)
  (:use [pallet.target :only [with-target-template]]
        clojure.test
        pallet.test-utils))

(with-private-vars [pallet.script [matches? more-explicit?]]
  (deftest matches?-test
    (with-target-template [:ubuntu]
      (is (matches? [:ubuntu]))
      (is (not (matches? [:fedora])))
      (is (not (matches? [:ubuntu :smallest]))))
    (with-target-template [:ubuntu :smallest]
      (is (matches? [:ubuntu]))
      (is (matches? [:smallest]))
      (is (not (matches? [:fedora])))
      (is (matches? [:ubuntu :smallest]))))

  (deftest more-explicit?-test
    (is (more-explicit? :default [:anything]))
    (is (more-explicit? [:something] [:anything :longer]))
    (is (not (more-explicit? [:something :longer] [:anything])))))
