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
                 (when (== "a" "b")
                   (exec-script/exec-script "c"))))))
  (is (=
       (str "if [ 1 -eq 1 ]; then\ntouch /root/my-touch1\nfi\n"
            "if [ 1 -eq 1 ]; then\ntouch /root/my-touch2\nfi\n")
       (first
        (build-actions
         {}
         (when "1 -eq 1"
           (exec-script/exec-script
            ("touch /root/my-touch1")))
         (when "1 -eq 1"
           (exec-script/exec-script
            ("touch /root/my-touch2"))))))))

(deftest when-not-test
  (is (= "if [ ! \\( \"a\" == \"b\" \\) ]; then\nc\nfi\n"
         (first (build-actions
                 {}
                 (when-not
                  (== "a" "b")
                  (exec-script/exec-script "c")))))))
