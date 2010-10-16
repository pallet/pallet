(ns pallet.crate.ssh-test
  (:use pallet.crate.ssh)
  (:require
   [pallet.crate.iptables :as iptables]
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest iptables-accept-test
  []
  (is (= (first
          (test-utils/build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (iptables/iptables-accept-port 22 "tcp")))
         (first
          (test-utils/build-resources
           [:node-type {:tag :n :image {:os-family :ubuntu}}]
           (iptables-accept))))))

(deftest invoke-test
  (is (test-utils/build-resources
       [:target-node (test-utils/make-node "tag" :id "id")]
       (openssh)
       (sshd-config "")
       (iptables-accept)
       (iptables-throttle)
       (nagios-monitor))))
