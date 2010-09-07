(ns pallet.crate.ssh-test
  (:use pallet.crate.ssh)
  (:require
   [pallet.crate.iptables :as iptables]
   [pallet.compute :as compute]
   [pallet.target :as target]
   [pallet.resource :as resource])
  (:use clojure.test
        pallet.test-utils))

(deftest iptables-accept-test
  []
  (is (= (first
          (resource/build-resources
           [:node-type {:tag :n :image [:ubuntu]}]
           (iptables/iptables-accept-port 22 "tcp")))
         (first
          (resource/build-resources
           [:node-type {:tag :n :image [:ubuntu]}]
           (iptables-accept))))))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (compute/make-node "tag" :id "id")]
       (openssh)
       (sshd-config "")
       (iptables-accept)
       (iptables-throttle)
       (nagios-monitor))))
