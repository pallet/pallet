(ns pallet.compute-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute :refer :all]))

(deftest packager-test
  (is (= :apt (packager-for-os :ubuntu nil)))
  (is (= :yum (packager-for-os :centos nil)))
  (is (= :portage (packager-for-os :gentoo nil))))

(defmulti-os testos [session])
(defmethod testos :linux [session] :linux)
(defmethod testos :debian [session] :debian)
(defmethod testos :rh-base [session] :rh-base)

(deftest defmulti-os-test
  (is (= :linux (testos {:server {:image {:os-family :arch}}})))
  (is (= :rh-base (testos {:server {:image {:os-family :centos}}})))
  (is (= :debian (testos {:server {:image {:os-family :debian}}})))
  (is (thrown? clojure.lang.ExceptionInfo
               (testos {:server {:image {:os-family :unspecified}}}))))
