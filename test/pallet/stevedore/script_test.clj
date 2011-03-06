(ns pallet.stevedore.script-test
  (:require
   pallet.script
   pallet.stevedore
   clojure.contrib.condition)
  (:use
   pallet.stevedore.script
   clojure.test
   pallet.test-utils))

(pallet.script/defscript x [a])

(deftest dispatch-test
  (testing "with no implementation"
    (testing "and no dispatch"
      (pallet.stevedore/with-no-script-fn-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (= "x 2" (pallet.stevedore/script (x 2)))))))
    (testing "and optional dispatch"
      (pallet.stevedore/with-script-fn-dispatch
        script-fn-optional-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (= "x 2" (pallet.stevedore/script (x 2)))))))
    (testing "and mandatory dispatch"
      (pallet.stevedore/with-script-fn-dispatch
        script-fn-mandatory-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (thrown? clojure.contrib.condition.Condition
                       (pallet.stevedore/script (x 2))))))))
  (testing "with an implementation"
    (defimpl x :default [a] (str "x" ~a 1))
    (testing "and no dispatch"
      (pallet.stevedore/with-no-script-fn-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (= "x 2" (pallet.stevedore/script (x 2)))))))
    (testing "and optional dispatch"
      (pallet.stevedore/with-script-fn-dispatch
        script-fn-optional-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (= "x21" (pallet.stevedore/script (x 2)))))))
    (testing "and mandatory dispatch"
      (pallet.stevedore/with-script-fn-dispatch
        script-fn-mandatory-dispatch
        (pallet.script/with-script-context [:ubuntu]
          (is (= "x21" (pallet.stevedore/script (x 2)))))))))
