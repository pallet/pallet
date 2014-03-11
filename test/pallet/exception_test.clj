(ns pallet.exception-test
  (:require
   [clojure.test :refer :all]
   [pallet.exception :refer :all]))

(deftest combine-exceptions-test
  (testing "with an exception"
    (let [e (ex-info "some error" {})]
      (is (not (domain-error? e)))
      (let [ce (combine-exceptions [e])]
        (is (not (domain-error? ce)) "combines to a non-domain exception"))))
  (testing "with an domain exception"
    (let [e (domain-info "some error" {})]
      (is (domain-error? e))
      (let [ce (combine-exceptions [e])]
        (is (domain-error? ce) "combines to a domain exception"))))
  (testing "with a non-domain and an domain exception"
    (let [e1 (ex-info "some error" {})
          e2 (domain-info "some error" {})]
      (let [ce (combine-exceptions [e1 e2])]
        (is (not (domain-error? ce)) "combines to a non-domain exception")))))
