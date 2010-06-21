(ns pallet.crate.iptables
  "Crate for managing iptables"
  (:require
   pallet.target
   pallet.resource
   pallet.resource.remote-file
   pallet.resource.file
   [clojure.contrib.string :as string]))

(def prefix
     "*filter
:INPUT ACCEPT
:FORWARD ACCEPT
:OUTPUT ACCEPT
:FWR -
-A INPUT -j FWR
-A FWR -i lo -j ACCEPT
")
(def suffix
     "# Rejects all remaining connections with port-unreachable errors.
-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable
-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable
COMMIT
")


(defn iptables*
  "Combine all iptables rules"
  [args]
  (let [table (string/join
               \newline [prefix
                         (string/join
                          \newline
                          (map #(string/join \newline %) args))
                         suffix])
        packager (pallet.target/packager)]
    (condp = packager
        :aptitude (pallet.stevedore/script
                   (var tmp @(mktemp iptablesXXXX))
                   ~(pallet.resource.remote-file/remote-file*
                     "$tmp" :content table)
                   ("/sbin/iptables-restore" < @tmp)
                   (rm @tmp))
        :yum (pallet.resource.remote-file/remote-file
              "/etc/sysconfig/iptables"
              :mode "0755"
              :content table))))

(pallet.resource/defaggregate iptables-rule
  "Define a rule for the iptables. The argument should be a string containing an
iptables configuration line (cf. arguments to an iptables invocation)"
  iptables* [config-line])

(defn iptables-accept-established
  "Accept established connections"
  []
  (iptables-rule "-A FWR -m state --state RELATED,ESTABLISHED -j ACCEPT"))

(defn iptables-accept-icmp
  "Accept ICMP"
  []
  (iptables-rule "-A FWR -p icmp -j ACCEPT"))

(defn iptables-accept-port
  "Accept specific port, by default for tcp."
  ([port] (iptables-accept-port port "tcp"))
  ([port protocol]
     (iptables-rule
      (format "-A FWR -p %s --dport %s -j ACCEPT"
              protocol port))))

(defn iptables-throttle
  "Throttle repeated connection attempts.
   http://hostingfu.com/article/ssh-dictionary-attack-prevention-with-iptables"
  ([name port] (iptables-throttle name port "tcp" 60 4))
  ([name port protocol time-period hitcount]
     (pallet.crate.iptables/iptables-rule
      (format
       "-N %s
-A FWR -p %s --dport %s -m state --state NEW -j %s
-A %s -m recent --set --name %s
-A %s -m recent --update --seconds %s --hitcount %s --name %s -j DROP"
       name protocol port name name name name time-period hitcount name))))

