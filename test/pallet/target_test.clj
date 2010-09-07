(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test
        pallet.test-utils))


(deftest os-family-test
  (is (= :ubuntu (os-family [:ubuntu]))))

(deftest packager-test
  (is (= :aptitude (packager [:ubuntu])))
  (is (= :yum (packager [:centos])))
  (is (= :portage (packager [:gentoo]))))
