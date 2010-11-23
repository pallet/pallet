(ns pallet.resource.resource-when-test
  (:use pallet.resource.resource-when)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource.test-resource :only [test-component]]
        clojure.test
        pallet.test-utils))

(deftest resource-when-test
  (is (= "if [ \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-resources
                 []
                 (resource-when
                  (== "a" "b")
                  (test-component "c")))))))

(deftest resource-when-not-test
  (is (= "if [ ! \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-resources
                 []
                 (resource-when-not
                  (== "a" "b")
                  (test-component "c")))))))
