(ns pallet.configure-test
  (:use pallet.configure)
  (:use
   clojure.test))

(deftest compute-service-properties-test
  (testing "default"
    (is (= {:provider "p" :identity "i" :credential "c"}
           (compute-service-properties
            {:provider "p" :identity "i" :credential "c"}
            []))))
  (testing "default with endpoint"
    (is (= {:provider "p" :identity "i" :credential "c" :endpoint "e"}
         (compute-service-properties
          {:provider "p" :identity "i" :credential "c" :endpoint "e"}
          []))))
  (testing "specified"
    (is (= {:provider "pb" :identity "ib" :credential "cb"}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            [:b])))
    (is (= {:provider "pb" :identity "ib" :credential "cb"}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            [:b]))))
  (testing "specified with endpoint"
    (is (= {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            [:a])))
    (is (= {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca" :endpoint "ea"}
              :b {:provider "pb" :identity "ib" :credential "cb"}}}
            [:a]))))
  (testing "with environment"
    (is (= {:provider "pa" :identity "ia" :credential "ca"
            :environment {:image {:os-family :ubuntu}}}
           (compute-service-properties
            {:providers
             {:a {:provider "pa" :identity "ia" :credential "ca"
                  :environment {:image {:os-family :ubuntu}}}}}
            [:a])))
    (is (= {:provider "pa" :identity "ia" :credential "ca"
            :environment {:image {:os-family :ubuntu :os-version "101"}}}
           (compute-service-properties
            {:services
             {:a {:provider "pa" :identity "ia" :credential "ca"
                  :environment {:image {:os-family :ubuntu}}}}
             :environment {:image {:os-version "101"}}}
            [:a])))))
