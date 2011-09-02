(ns pallet.compute-test
  (:use pallet.compute)
  (:use clojure.test))

(deftest packager-test
  (is (= :aptitude (packager {:os-family :ubuntu})))
  (is (= :yum (packager {:os-family :centos})))
  (is (= :portage (packager {:os-family :gentoo}))))

(deftest base-distribution-test
  (is (= :debian (base-distribution {:os-family :ubuntu})))
  (is (= :rh (base-distribution {:os-family :centos})))
  (is (= :gentoo (base-distribution {:os-family :gentoo})))
  (is (= :arch (base-distribution {:os-family :arch})))
  (is (= :suse (base-distribution {:os-family :suse}))))

