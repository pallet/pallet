(ns pallet.resource.resource-when-test
  (:use pallet.resource.resource-when)
  (:require
   [pallet.action :as action]
   [pallet.resource.exec-script :as exec-script])
  (:use
   clojure.test
   pallet.build-actions
   pallet.test-utils))

(deftest resource-when-test
  (is (= "if [ \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-actions
                 []
                 (resource-when
                  (== "a" "b")
                  (exec-script/exec-script "c")))))))

(deftest resource-when-not-test
  (is (= "if [ ! \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-actions
                 []
                 (resource-when-not
                  (== "a" "b")
                  (exec-script/exec-script "c")))))))
