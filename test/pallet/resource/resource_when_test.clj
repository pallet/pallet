(ns pallet.resource.resource-when-test
  (:use pallet.resource.resource-when)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        [pallet.resource.test-resource :only [test-component]]
        clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest exec-when*-test
  (is (= 1 (exec-when* (fn [] 1)))))

(deftest resource-when-test
  (is (= "if [ \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (build-resources []
          (resource-when (== "a" "b")
                         (test-component "c"))))))

(deftest resource-when-not-test
  (is (= "if [ ! \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (build-resources []
          (resource-when-not (== "a" "b")
                         (test-component "c"))))))
