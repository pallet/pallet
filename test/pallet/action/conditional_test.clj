(ns pallet.action.conditional-test
  (:refer-clojure :exclude [when when-not])
  (:use pallet.action.conditional)
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script])
  (:use
   clojure.test
   pallet.build-actions
   pallet.test-utils))

(deftest when-test
  (is (= "if [ \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-actions
                 {}
                 (when
                  (== "a" "b")
                  (exec-script/exec-script "c")))))))

(deftest when-not-test
  (is (= "if [ ! \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-actions
                 {}
                 (when-not
                  (== "a" "b")
                  (exec-script/exec-script "c")))))))
