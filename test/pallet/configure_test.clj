(ns pallet.configure-test
  (:require
   [clojure.java.io :refer [file]]
   [clojure.test :refer :all]
   [pallet.api]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute :refer [nodes]]
   [pallet.configure :refer :all]
   [pallet.utils :refer [tmpdir]]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest compute-service-properties-test
  (testing "default"
    (is (= {:provider "p" :identity "i" :credential "c"}
           (compute-service-properties
            {:provider "p" :identity "i" :credential "c"}
            :pallet.configure/default))))
  (testing "default with endpoint"
    (is (= {:provider "p" :identity "i" :credential "c" :endpoint "e"}
         (compute-service-properties
          {:provider "p" :identity "i" :credential "c" :endpoint "e"}
          :pallet.configure/default))))
  (testing "specified"
    (is (= {:provider "pb" :identity "ib" :credential "cb"}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            :b)))
    (is (= {:provider "pb" :identity "ib" :credential "cb"}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            :b))))
  (testing "specified with endpoint"
    (is (= {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            :a)))
    (is (= {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            :a))))
  (testing "with environment"
    (is (= {:provider "pa" :identity "ia" :credential "ca"
            :environment {:image {:os-family :ubuntu}}}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca"
                  :environment {:image {:os-family :ubuntu}}}}}
            :a)))
    (is (= {:provider "pa" :identity "ia" :credential "ca"
            :environment {:image {:os-family :ubuntu :os-version "101"}}}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca"
                  :environment {:image {:os-family :ubuntu}}}}
             :environment {:image {:os-version "101"}}}
            :a)))))

;;; define service in pallet.config
(ns pallet.config)
(def service :service)
(in-ns 'pallet.configure-test)

(deftest compute-service-from-config-var-test
  (is (= :service (compute-service-from-config-var))))

(def property-service :property-service)
(deftest compute-service-from-property-test
  (System/setProperty
   "pallet.config.service" "pallet.configure-test/property-service")
  (is (= :property-service (compute-service-from-property))))

;;; define user in pallet.config
(in-ns 'pallet.config)
(def admin-user (pallet.api/make-user "fred"))
(in-ns 'pallet.configure-test)

(deftest admin-user-from-config-var-test
  (let [admin-user (admin-user-from-config-var)]
    (is (= "fred" (:username admin-user)))))

(deftest admin-user-from-config-test
  (let [admin-user (admin-user-from-config {:admin-user {:username "fred"}})]
    (is (= "fred" (:username admin-user)))))

(def nl-service-form
  '{:nl {:provider :node-list
         :node-list [["worker" "worker" "192.168.1.37"
                      :ubuntu :os-version "10.04"
                      :is-64bit false]]}})
(def nl-form
  '(defpallet
     :services {:nl {:provider :node-list
                     :node-list [["worker" "worker" "192.168.1.37"
                                  :ubuntu :os-version "10.04"
                                  :is-64bit false]]}}))

(deftest compute-service-test
  (let [tmp (tmpdir)
        clean-tmp (fn []
                    (doseq [f (file-seq tmp)]
                      (.delete f))
                    (.delete tmp))
        home (System/getProperty "user.home")
        ^java.io.File pallet (file tmp ".pallet" "config.clj")
        ^java.io.File service (file tmp ".pallet" "services" "xx.clj")
        ^java.io.File ds_store (file tmp ".pallet" "services" ".DS_Store")]
    (try
      (System/setProperty "user.home" (.getPath tmp))
      (is (not= home (System/getProperty "user.home")))
      (is (.isDirectory tmp))
      (.mkdirs (.getParentFile pallet))
      (.mkdirs (.getParentFile service))
      (is (compute-service :test) "from resource")
      (testing "config.clj"
        (spit pallet nl-form)
        (is (= 1 (count (nodes (compute-service :nl)))) "from config file")
        (is (zero? (count (nodes (compute-service :nl :node-list []))))
            "override options")
        (.delete pallet))
      (testing "services/xx.clj"
        (spit service nl-service-form)
        (is (= 1 (count (nodes (compute-service :nl)))) "from services file")
        (testing "services/.DS_Store doesn't cause error"
          (spit ds_store "fred")
          (is (= 1 (count (nodes (compute-service :nl)))) "from services file")
          (.delete ds_store))
        (.delete service))
      (finally
        (System/setProperty "user.home" home)
        (clean-tmp)))))
