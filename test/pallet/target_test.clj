(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test
        pallet.test-utils))


(deftest os-family-test
  (is (= :ubuntu (os-family {:os-family :ubuntu}))))

(deftest packager-test
  (is (= :aptitude (packager {:os-family :ubuntu})))
  (is (= :yum (packager {:os-family :centos})))
  (is (= :portage (packager {:os-family :gentoo}))))
