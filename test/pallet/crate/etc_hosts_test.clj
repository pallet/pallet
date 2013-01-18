(ns pallet.crate.etc-hosts-test
 (:use
  clojure.test
  [pallet.actions :only [remote-file]]
  [pallet.build-actions :only [build-actions]]
  [pallet.common.logging.logutils :only [logging-threshold-fixture]])
 (:require
  [pallet.crate.etc-hosts :as etc-hosts]
  [pallet.test-utils :as test-utils]))

(use-fixtures :once (logging-threshold-fixture))

(deftest format-hosts*-test
  (is (= "1.2.3.4 some-host\n1.2.3.5 some-other-host"
         (#'pallet.crate.etc-hosts/format-hosts*
          {"1.2.3.4" ["some-host"]
           "1.2.3.5" [:some-other-host]}))))

(deftest hostname-test
  (testing "compile"
    (is
     (build-actions
      {:server {:node (test-utils/make-node "g" :group-name :grp)}}
      (etc-hosts/set-hostname)))))
