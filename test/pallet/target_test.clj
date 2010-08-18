(ns pallet.target-test
  (:use pallet.target)
  (:use clojure.test
        pallet.test-utils))

(deftest with-target-test
  (with-target nil {:image [:ubuntu]
                    :tag :ubuntu}
    (is (= [:ubuntu] (template)))
    (is (= :ubuntu (tag)))))

(deftest os-family-test
  (is (= :ubuntu (os-family [:ubuntu])))
  (with-target nil {:image [:ubuntu]
                    :tag :ubuntu}
    (is (= :ubuntu (os-family)))))

(deftest packager-test
  (with-target nil {:image [:ubuntu]}
    (is (= :aptitude (packager))))
  (is (= :aptitude (packager [:ubuntu])))
  (is (= :yum (packager [:centos])))
  (is (= :portage (packager [:gentoo]))))
