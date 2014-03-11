(ns pallet.kb-test
  (:require
   [clojure.test :refer :all]
   [pallet.kb :refer :all]))

(deftest packager-test
  (is (= :apt (packager-for-os :ubuntu nil)))
  (is (= :yum (packager-for-os :centos nil)))
  (is (= :portage (packager-for-os :gentoo nil)))
  (is (= :brew (packager-for-os :os-x nil))))
