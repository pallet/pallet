(ns pallet.crate.ssh-test
  (:use pallet.crate.ssh)
  (:require
   pallet.crate.iptables
   [pallet.target :as target]
   [pallet.resource :as resource])
  (:use clojure.test
        pallet.test-utils))

(deftest iptables-accept-test
  []
  (is (= (target/with-target nil {:tag :n :image [:ubuntu]}
           (resource/build-resources
            [] (pallet.crate.iptables/iptables-accept-port 22 "tcp")))
         (target/with-target nil {:tag :n :image [:ubuntu]}
           (resource/build-resources
            [] (iptables-accept))))))
