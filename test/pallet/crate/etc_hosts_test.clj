(ns pallet.crate.etc-hosts-test
 (:use
  clojure.test)
 (:require
  [pallet.build-actions :as build-actions]
  [pallet.crate.etc-hosts :as etc-hosts]
  [pallet.test-utils :as test-utils]))

(deftest format-hosts*-test
  (is (= "1.2.3.4 some-host\n1.2.3.5 some-other-host"
         (#'pallet.crate.etc-hosts/format-hosts*
          {"1.2.3.4" "some-host"
           "1.2.3.5" :some-other-host}))))

(deftest simple-test
  (is (build-actions/build-actions
       {:server {:node (test-utils/make-node "g")}}
       (etc-hosts/hosts-for-group :g)
       (etc-hosts/hosts))))
