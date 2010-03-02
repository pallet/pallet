(ns pallet.utils-test
  (:use [pallet.utils] :reload-all)
  (:use clojure.test
        pallet.test-utils))

(deftest system-test
  (is (= {:exit 0 :out "" :err ""} (system "/usr/bin/true"))))

(deftest bash-test
  (is (= {:exit 0 :out "fred\n" :err ""} (bash "echo fred"))))
