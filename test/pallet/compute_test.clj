(ns pallet.compute-test
  (:use pallet.compute)
  (:use clojure.test
        [pallet.common.slingshot-test-util :only [is-thrown-slingshot?]]))

(deftest packager-test
  (is (= :aptitude (packager {:os-family :ubuntu})))
  (is (= :yum (packager {:os-family :centos})))
  (is (= :portage (packager {:os-family :gentoo}))))

(deftest base-distribution-test
  (is (= :debian (base-distribution {:os-family :ubuntu})))
  (is (= :rh (base-distribution {:os-family :centos})))
  (is (= :gentoo (base-distribution {:os-family :gentoo})))
  (is (= :arch (base-distribution {:os-family :arch})))
  (is (= :suse (base-distribution {:os-family :suse})))
  (is (= :solaris (base-distribution {:os-family :smartos}))))


(defmulti-os testos [session])
(defmethod testos :linux [session] :linux)
(defmethod testos :debian [session] :debian)
(defmethod testos :rh-base [session] :rh-base)
(defmethod testos :solaris [session] :solaris)

(deftest defmulti-os-test
  (is (= :linux (testos {:server {:image {:os-family :arch}}})))
  (is (= :rh-base (testos {:server {:image {:os-family :centos}}})))
  (is (= :debian (testos {:server {:image {:os-family :debian}}})))
  (is (= :solaris (testos {:server {:image {:os-family :smartos}}})))
  (is-thrown-slingshot?
   (testos {:server {:image {:os-family :unspecified}}})))
