(ns pallet.crate.ssh-test
  (:use pallet.crate.ssh)
  (:require
   [pallet.crate.iptables :as iptables]
   [pallet.compute.jclouds :as jclouds]
   [pallet.target :as target]
   [pallet.resource :as resource])
  (:use clojure.test
        pallet.test-utils))

(deftest iptables-accept-test
  []
  (is (= (first
          (build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (iptables/iptables-accept-port 22 "tcp")))
         (first
          (build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (iptables-accept))))))

(deftest invoke-test
  (is (build-resources
       [:target-node (jclouds/make-node "tag" :id "id")]
       (openssh)
       (sshd-config "")
       (iptables-accept)
       (iptables-throttle)
       (nagios-monitor))))
