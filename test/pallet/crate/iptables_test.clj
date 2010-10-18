(ns pallet.crate.iptables-test
  (:use pallet.crate.iptables)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(deftest iptables-test
  []
  (testing "debian"
    (is (= (stevedore/do-script
            (stevedore/script (var tmp @(mktemp iptablesXXXX)))
            (pallet.resource.remote-file/remote-file*
             {}
             "$tmp"
             :content
             "*filter\n:INPUT ACCEPT\n:FORWARD ACCEPT\n:OUTPUT ACCEPT\n:FWR -\n-A INPUT -j FWR\n-A FWR -i lo -j ACCEPT\nf1\nf2\n# Rejects all remaining connections with port-unreachable errors.\n-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable\n-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable\nCOMMIT\n")
            (stevedore/checked-script
             "Restore IPtables"
             ("/sbin/iptables-restore" < @tmp))
            (stevedore/script (rm @tmp)))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :ubuntu}}]
             (iptables-rule "filter" "f1")
             (iptables-rule "filter" "f2"))))))
  (testing "redhat"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (pallet.resource.remote-file/remote-file
              "/etc/sysconfig/iptables"
              :content
              "*filter\n:INPUT ACCEPT\n:FORWARD ACCEPT\n:OUTPUT ACCEPT\n:FWR -\n-A INPUT -j FWR\n-A FWR -i lo -j ACCEPT\n\n# Rejects all remaining connections with port-unreachable errors.\n-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable\n-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable\nCOMMIT\n"
              :mode "0755")))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-rule "filter" "")))))))


(deftest iptables-redirect-port-test
  (testing "redirect with default protocol"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-rule
              "nat"
              "-I PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8081")))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-redirect-port 80 8081)))))))

(deftest iptables-accept-port-test
  (testing "accept with default protocol"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-rule
              "filter"
              "-A FWR -p tcp --dport 80 -j ACCEPT")))
         (first
          (build-resources
           [:node-type {:tag :n :image {:os-family :centos}}]
           (iptables-accept-port 80))))))
  (testing "accept with source"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-rule
              "filter"
              "-A FWR -p tcp -s 1.2.3.4 --dport 80 -j ACCEPT")))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-accept-port 80 "tcp" :source "1.2.3.4"))))))
  (testing "accept with source range"
    (is (= (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-rule
              "filter"
              (str "-A FWR -p tcp -src-range 11.22.33.10-11.22.33.50"
                   " --dport 80 -j ACCEPT"))))
           (first
            (build-resources
             [:node-type {:tag :n :image {:os-family :centos}}]
             (iptables-accept-port
              80 "tcp" :source-range "11.22.33.10-11.22.33.50")))))))

(deftest invocation-test
  (is (build-resources
       [:node-type {:image {:os-family :ubuntu}}]
       (iptables-accept-established)
       (iptables-accept-icmp)
       (iptables-accept-port 80)
       (iptables-redirect-port 80 81)
       (iptables-throttle "a" 80))))
