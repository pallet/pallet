(ns pallet.maven-test
  (:use pallet.maven)
  (:use
   clojure.test
   pallet.test-utils))

(deftest read-maven-settings-test
  (is (#'pallet.maven/make-settings)))

;; test calling these, even if we haven't set up expected values
(deftest credentials-test
  (credentials))

(deftest properties-test
  (is (map? (properties))))
