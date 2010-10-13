(ns pallet.crate.etc-hosts-test
 (:use
  clojure.test
  pallet.test-utils)
 (:require
  [pallet.crate.etc-hosts :as hosts]
  [pallet.resource :as resource]
  [pallet.stevedore :as stevedore]
  [pallet.resource.remote-file :as remote-file]
  [pallet.target :as target]))

(deftest format-hosts*-test
  (is (= "1.2.3.4 some-host\n1.2.3.5 some-other-host"
         (#'pallet.crate.etc-hosts/format-hosts*
          {"1.2.3.4" "some-host"
           "1.2.3.5" :some-other-host}))))
