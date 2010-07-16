(ns pallet.crate.iptables-test
  (:use pallet.crate.iptables :reload-all)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest site-test
  []
  (is (= (stevedore/do-script
          (stevedore/script (var tmp @(mktemp iptablesXXXX)))
          (pallet.resource.remote-file/remote-file*
           "$tmp"
           :content
           "*filter\n:INPUT ACCEPT\n:FORWARD ACCEPT\n:OUTPUT ACCEPT\n:FWR -\n-A INPUT -j FWR\n-A FWR -i lo -j ACCEPT\n\n\n# Rejects all remaining connections with port-unreachable errors.\n-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable\n-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable\nCOMMIT\n")
          (stevedore/checked-script
           "Restore IPtables"
           ("/sbin/iptables-restore" < @tmp))
          (stevedore/script (rm @tmp)))
         (target/with-target nil {:tag :n :image [:ubuntu]}
           (resource/build-resources
            [] (iptables-rule ""))))))
