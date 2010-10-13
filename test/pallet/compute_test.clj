(ns pallet.compute-test
  (:use pallet.compute)
  (require
   [pallet.utils]
   [pallet.compute.jclouds :as jclouds])
  (:use clojure.test))

(deftest node-counts-by-tag-test
  (is (= {:a 2}
         (node-counts-by-tag
          [(jclouds/make-node "a") (jclouds/make-node "a")]))))

(deftest packager-test
  (is (= :aptitude (packager {:os-family :ubuntu})))
  (is (= :yum (packager {:os-family :centos})))
  (is (= :portage (packager {:os-family :gentoo}))))
