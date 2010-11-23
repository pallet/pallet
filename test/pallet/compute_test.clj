(ns pallet.compute-test
  (:use pallet.compute)
  (:use clojure.test))

(deftest packager-test
  (is (= :aptitude (packager {:os-family :ubuntu})))
  (is (= :yum (packager {:os-family :centos})))
  (is (= :portage (packager {:os-family :gentoo}))))

;;; define service in pallet.config
(ns pallet.config)
(def service :service)
(in-ns 'pallet.compute-test)

(deftest compute-service-from-config-var-test
  (is (= :service (compute-service-from-config-var))))

(def property-service :property-service)
(deftest compute-service-from-property-test
  (System/setProperty
   "pallet.config.service" "pallet.compute-test/property-service")
  (is (= :property-service (compute-service-from-property))))

(deftest compute-service-from-var-test
  (testing "catch"
    (is (nil? (#'pallet.compute/compute-service-from-var
               'this.does.not 'exist)))))
